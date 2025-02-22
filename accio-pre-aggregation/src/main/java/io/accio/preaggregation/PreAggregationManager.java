/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.accio.preaggregation;

import com.google.common.collect.ImmutableList;
import io.accio.base.AccioException;
import io.accio.base.AccioMDL;
import io.accio.base.CatalogSchemaTableName;
import io.accio.base.ConnectorRecordIterator;
import io.accio.base.Parameter;
import io.accio.base.SessionContext;
import io.accio.base.client.duckdb.DuckdbClient;
import io.accio.base.dto.PreAggregationInfo;
import io.accio.base.sql.SqlConverter;
import io.accio.preaggregation.dto.PreAggregationTable;
import io.accio.sqlrewrite.AccioPlanner;
import io.airlift.log.Logger;
import io.trino.sql.parser.ParsingOptions;
import io.trino.sql.parser.SqlParser;
import io.trino.sql.tree.Statement;

import javax.annotation.PreDestroy;
import javax.inject.Inject;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.function.Predicate;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.accio.base.metadata.StandardErrorCode.GENERIC_USER_ERROR;
import static io.accio.preaggregation.TaskInfo.TaskStatus.DONE;
import static io.accio.preaggregation.TaskInfo.TaskStatus.RUNNING;
import static io.airlift.concurrent.Threads.daemonThreadsNamed;
import static io.airlift.concurrent.Threads.threadsNamed;
import static io.trino.execution.sql.SqlFormatterUtil.getFormattedSql;
import static java.lang.String.format;
import static java.lang.System.currentTimeMillis;
import static java.util.Objects.requireNonNull;
import static java.util.UUID.randomUUID;
import static java.util.concurrent.CompletableFuture.runAsync;
import static java.util.concurrent.CompletableFuture.supplyAsync;
import static java.util.concurrent.Executors.newCachedThreadPool;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.stream.Collectors.toList;

public class PreAggregationManager
{
    private static final Logger LOG = Logger.get(PreAggregationManager.class);
    private static final ParsingOptions PARSE_AS_DECIMAL = new ParsingOptions(ParsingOptions.DecimalLiteralTreatment.AS_DECIMAL);
    private final ExtraRewriter extraRewriter;
    private final PreAggregationService preAggregationService;
    private final SqlParser sqlParser;
    private final SqlConverter sqlConverter;
    private final DuckdbClient duckdbClient;
    private final PreAggregationStorageConfig preAggregationStorageConfig;
    private final ConcurrentLinkedQueue<PathInfo> tempFileLocations = new ConcurrentLinkedQueue<>();
    private final PreAggregationTableMapping preAggregationTableMapping;
    private final ConcurrentMap<CatalogSchemaTableName, ScheduledFuture<?>> preAggregationScheduledFutures = new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor refreshExecutor = new ScheduledThreadPoolExecutor(5, daemonThreadsNamed("pre-aggregation-refresh-%s"));

    private final ExecutorService executorService = newCachedThreadPool(threadsNamed("pre-aggregation-manager-%s"));
    private final ConcurrentHashMap<String, Task> tasks = new ConcurrentHashMap<>();

    @Inject
    public PreAggregationManager(
            SqlConverter sqlConverter,
            PreAggregationService preAggregationService,
            ExtraRewriter extraRewriter,
            DuckdbClient duckdbClient,
            PreAggregationStorageConfig preAggregationStorageConfig,
            PreAggregationTableMapping preAggregationTableMapping)
    {
        this.sqlParser = new SqlParser();
        this.sqlConverter = requireNonNull(sqlConverter, "sqlConverter is null");
        this.preAggregationService = requireNonNull(preAggregationService, "preAggregationService is null");
        this.extraRewriter = requireNonNull(extraRewriter, "extraRewriter is null");
        this.duckdbClient = requireNonNull(duckdbClient, "duckdbClient is null");
        this.preAggregationStorageConfig = requireNonNull(preAggregationStorageConfig, "preAggregationStorageConfig is null");
        this.preAggregationTableMapping = requireNonNull(preAggregationTableMapping, "preAggregationTableMapping is null");
        refreshExecutor.setRemoveOnCancelPolicy(true);
    }

    private synchronized CompletableFuture<Void> refreshPreAggregation(AccioMDL mdl)
    {
        String catalogName = mdl.getCatalog();
        String schemaName = mdl.getSchema();
        List<TaskInfo> taskInfoList = listTaskInfo(catalogName, schemaName, Optional.of(true)).join();
        if (!taskInfoList.isEmpty()) {
            throw new AccioException(GENERIC_USER_ERROR, format("Pre-aggregation is already running; catalogName: %s, schemaName: %s", mdl.getCatalog(), mdl.getSchema()));
        }
        removePreAggregation(catalogName, schemaName);
        return doPreAggregation(mdl);
    }

    private CompletableFuture<Void> doPreAggregation(AccioMDL mdl)
    {
        List<CompletableFuture<Void>> futures = mdl.listPreAggregated()
                .stream()
                .map(preAggregationInfo ->
                        doSinglePreAggregation(mdl, preAggregationInfo)
                                .thenRun(() -> preAggregationScheduledFutures.put(
                                        new CatalogSchemaTableName(mdl.getCatalog(), mdl.getSchema(), preAggregationInfo.getName()),
                                        refreshExecutor.scheduleWithFixedDelay(
                                                () -> doSinglePreAggregation(mdl, preAggregationInfo).join(),
                                                preAggregationInfo.getRefreshTime().toMillis(),
                                                preAggregationInfo.getRefreshTime().toMillis(),
                                                MILLISECONDS))))
                .collect(toImmutableList());
        CompletableFuture<Void> allFutures = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        return allFutures.whenComplete((v, e) -> {
            if (e != null) {
                LOG.error(e, "Failed to do pre-aggregation");
            }
        });
    }

    public ConnectorRecordIterator query(String sql, List<Parameter> parameters)
            throws SQLException
    {
        return DuckdbRecordIterator.of(duckdbClient, sql, parameters.stream().collect(toImmutableList()));
    }

    private CompletableFuture<Void> doSinglePreAggregation(AccioMDL mdl, PreAggregationInfo preAggregationInfo)
    {
        CatalogSchemaTableName catalogSchemaTableName = new CatalogSchemaTableName(mdl.getCatalog(), mdl.getSchema(), preAggregationInfo.getName());
        String duckdbTableName = format("%s_%s", preAggregationInfo.getName(), randomUUID().toString().replace("-", ""));
        long createTime = currentTimeMillis();
        return runAsync(() -> {
            SessionContext sessionContext = SessionContext.builder()
                    .setCatalog(mdl.getCatalog())
                    .setSchema(mdl.getSchema())
                    .build();
            String accioRewritten = AccioPlanner.rewrite(
                    format("select * from %s", preAggregationInfo.getName()),
                    sessionContext,
                    mdl);
            Statement parsedStatement = sqlParser.createStatement(accioRewritten, PARSE_AS_DECIMAL);
            Statement rewrittenStatement = extraRewriter.rewrite(parsedStatement);

            createPreAggregation(mdl, preAggregationInfo, sessionContext, rewrittenStatement, duckdbTableName);
            preAggregationTableMapping.putPreAggregationTableMapping(catalogSchemaTableName, new PreAggregationInfoPair(preAggregationInfo, duckdbTableName, createTime));
        }).exceptionally(e -> {
            duckdbClient.dropTableQuietly(duckdbTableName);
            String errMsg = format("Failed to do pre-aggregation for preAggregationInfo %s; caused by %s", preAggregationInfo.getName(), e.getMessage());
            LOG.error(e, errMsg);
            preAggregationTableMapping.putPreAggregationTableMapping(catalogSchemaTableName, new PreAggregationInfoPair(preAggregationInfo, Optional.empty(), Optional.of(errMsg), createTime));
            return null;
        });
    }

    private void createPreAggregation(
            AccioMDL mdl,
            PreAggregationInfo preAggregationInfo,
            SessionContext sessionContext,
            Statement rewrittenStatement,
            String duckdbTableName)
    {
        preAggregationService.createPreAggregation(
                        mdl.getCatalog(),
                        mdl.getSchema(),
                        preAggregationInfo.getName(),
                        sqlConverter.convert(getFormattedSql(rewrittenStatement, sqlParser), sessionContext))
                .ifPresent(pathInfo -> {
                    try {
                        tempFileLocations.add(pathInfo);
                        refreshPreAggInDuckDB(pathInfo.getPath() + "/" + pathInfo.getFilePattern(), duckdbTableName);
                    }
                    finally {
                        removeTempFile(pathInfo);
                    }
                });
    }

    private void refreshPreAggInDuckDB(String path, String tableName)
    {
        duckdbClient.executeDDL(preAggregationStorageConfig.generateDuckdbParquetStatement(path, tableName));
    }

    public void removePreAggregation(String catalogName, String schemaName)
    {
        requireNonNull(catalogName, "catalogName is null");
        requireNonNull(schemaName, "schemaName is null");

        preAggregationScheduledFutures.keySet().stream()
                .filter(catalogSchemaTableName -> catalogSchemaTableName.getCatalogName().equals(catalogName)
                        && catalogSchemaTableName.getSchemaTableName().getSchemaName().equals(schemaName))
                .forEach(catalogSchemaTableName -> {
                    preAggregationScheduledFutures.get(catalogSchemaTableName).cancel(true);
                    preAggregationScheduledFutures.remove(catalogSchemaTableName);
                });

        preAggregationTableMapping.entrySet().stream()
                .filter(entry -> entry.getKey().getCatalogName().equals(catalogName)
                        && entry.getKey().getSchemaTableName().getSchemaName().equals(schemaName))
                .forEach(entry -> {
                    entry.getValue().getTableName().ifPresent(duckdbClient::dropTableQuietly);
                    preAggregationTableMapping.remove(entry.getKey());
                });
    }

    public boolean preAggregationScheduledFutureExists(CatalogSchemaTableName catalogSchemaTableName)
    {
        return preAggregationScheduledFutures.containsKey(catalogSchemaTableName);
    }

    @PreDestroy
    public void stop()
    {
        refreshExecutor.shutdown();
        cleanTempFiles();
    }

    public void cleanTempFiles()
    {
        try {
            List<PathInfo> locations = ImmutableList.copyOf(tempFileLocations);
            locations.forEach(this::removeTempFile);
        }
        catch (Exception e) {
            LOG.error(e, "Failed to clean temp file");
        }
    }

    public void removeTempFile(PathInfo pathInfo)
    {
        if (tempFileLocations.contains(pathInfo)) {
            preAggregationService.deleteTarget(pathInfo);
            tempFileLocations.remove(pathInfo);
        }
    }

    public TaskInfo createTaskUtilDone(AccioMDL mdl)
    {
        Optional<TaskInfo> taskInfoOptional = createTask(mdl)
                .thenCompose(taskInfo -> {
                    tasks.get(taskInfo.getTaskId()).waitUntilDone();
                    return getTaskInfo(taskInfo.getTaskId());
                })
                .join();
        return taskInfoOptional.orElseThrow(() -> new RuntimeException("Failed to create task"));
    }

    public CompletableFuture<TaskInfo> createTask(AccioMDL mdl)
    {
        return supplyAsync(() -> {
            String taskId = randomUUID().toString();
            TaskInfo taskInfo = new TaskInfo(taskId, mdl.getCatalog(), mdl.getSchema(), RUNNING, Instant.now());
            Task task = new Task(taskInfo, refreshPreAggregation(mdl));
            tasks.put(taskId, task);
            return taskInfo;
        });
    }

    public CompletableFuture<List<TaskInfo>> listTaskInfo(String catalogName, String schemaName, Optional<Boolean> inProgress)
    {
        Predicate<TaskInfo> catalogNamePred = catalogName.isEmpty() ?
                (t) -> true :
                (t) -> catalogName.equals(t.getCatalogName());

        Predicate<TaskInfo> schemaNamePred = schemaName.isEmpty() ?
                (t) -> true :
                (t) -> schemaName.equals(t.getSchemaName());

        Predicate<TaskInfo> inProgressPred = inProgress.isEmpty() ?
                (t) -> true :
                (t) -> t.inProgress() == inProgress.get();

        return supplyAsync(
                () -> tasks.values().stream()
                        .map(Task::getTaskInfo)
                        .filter(TaskInfo::inProgress)
                        .collect(toList()),
                executorService);
    }

    public CompletableFuture<Optional<TaskInfo>> getTaskInfo(String taskId)
    {
        requireNonNull(taskId);
        return supplyAsync(
                () -> tasks.containsKey(taskId) ?
                        Optional.of(tasks.get(taskId).getTaskInfo()) :
                        Optional.empty(),
                executorService);
    }

    private class Task
    {
        private final TaskInfo taskInfo;
        private final CompletableFuture<?> completableFuture;

        public Task(TaskInfo taskInfo, CompletableFuture<?> completableFuture)
        {
            this.taskInfo = taskInfo;
            this.completableFuture =
                    completableFuture
                            .thenRun(() -> {
                                taskInfo.setPreAggregationTables(
                                        preAggregationTableMapping.getPreAggregationInfoPairs(
                                                        taskInfo.getCatalogName(),
                                                        taskInfo.getSchemaName())
                                                .stream()
                                                .map(preAggregationInfoPair -> new PreAggregationTable(
                                                        preAggregationInfoPair.getPreAggregationInfo().getName(),
                                                        preAggregationInfoPair.getErrorMessage(),
                                                        preAggregationInfoPair.getPreAggregationInfo().getRefreshTime(),
                                                        Instant.ofEpochMilli(preAggregationInfoPair.getCreateTime())))
                                                .collect(toImmutableList()));
                                taskInfo.setTaskStatus(DONE);
                            });
        }

        public TaskInfo getTaskInfo()
        {
            return taskInfo;
        }

        public void waitUntilDone()
        {
            completableFuture.join();
        }
    }
}

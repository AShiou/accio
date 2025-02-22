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

package io.accio.main.pgcatalog.regtype;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;
import io.accio.main.metadata.Metadata;

import javax.inject.Inject;

import java.util.List;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.accio.main.sql.PgOidTypeTableInfo.REGCLASS;
import static io.accio.main.sql.PgOidTypeTableInfo.REGPROC;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public class BigQueryPgMetadata
        extends PgMetadata
{
    private final Metadata metadata;

    @Inject
    public BigQueryPgMetadata(Metadata metadata)
    {
        this.metadata = requireNonNull(metadata, "metadata is null");
    }

    @Override
    protected List<RegObject> listRegProc()
    {
        // TODO: change to use bigquery api to save cost
        // https://github.com/Canner/canner-metric-layer/issues/63
        return Streams.stream(metadata.directQuery(format("SELECT oid, %s FROM pg_catalog.%s", REGPROC.getNameField(), REGPROC.getTableName()), ImmutableList.of()))
                .map(row -> new RegProc((long) row[0], (String) row[1]))
                .collect(toImmutableList());
    }

    @Override
    protected List<RegObject> listRegClass()
    {
        // TODO: change to use bigquery api to save cost
        // https://github.com/Canner/canner-metric-layer/issues/63
        return Streams.stream(metadata.directQuery(format("SELECT oid, %s FROM pg_catalog.%s", REGCLASS.getNameField(), REGCLASS.getTableName()), ImmutableList.of()))
                .map(row -> new RegObjectImpl((long) row[0], (String) row[1]))
                .collect(toImmutableList());
    }
}

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

package io.accio.server;

import com.google.common.collect.ImmutableList;
import com.google.inject.Injector;
import com.google.inject.Module;
import io.accio.main.AccioConfig;
import io.accio.main.AccioModule;
import io.accio.main.pgcatalog.PgCatalogManager;
import io.accio.main.server.Server;
import io.accio.main.server.module.BigQueryConnectorModule;
import io.accio.main.server.module.PostgresConnectorModule;
import io.accio.main.server.module.PostgresWireProtocolModule;
import io.accio.main.server.module.WebModule;
import io.accio.main.wireprotocol.ssl.EmptyTlsDataProvider;
import io.accio.preaggregation.PreAggregationModule;
import io.airlift.event.client.EventModule;
import io.airlift.http.server.HttpServerModule;
import io.airlift.jaxrs.JaxrsModule;
import io.airlift.json.JsonModule;
import io.airlift.node.NodeModule;

import static io.accio.main.AccioConfig.DataSourceType.BIGQUERY;
import static io.accio.main.AccioConfig.DataSourceType.POSTGRES;
import static io.airlift.configuration.ConditionalModule.conditionalModule;

public class AccioServer
        extends Server
{
    public static void main(String[] args)
    {
        new AccioServer().start();
    }

    @Override
    protected void configure(Injector injector)
    {
        PgCatalogManager pgCatalogManager = injector.getInstance(PgCatalogManager.class);
        pgCatalogManager.initPgCatalog();
    }

    @Override
    protected Iterable<? extends Module> getAdditionalModules()
    {
        return ImmutableList.of(
                new NodeModule(),
                new HttpServerModule(),
                new JsonModule(),
                new JaxrsModule(),
                new EventModule(),
                new PostgresWireProtocolModule(new EmptyTlsDataProvider()),
                conditionalModule(AccioConfig.class, config -> config.getDataSourceType().equals(BIGQUERY), new BigQueryConnectorModule()),
                conditionalModule(AccioConfig.class, config -> config.getDataSourceType().equals(POSTGRES), new PostgresConnectorModule()),
                new AccioModule(),
                new PreAggregationModule(),
                new WebModule());
    }
}

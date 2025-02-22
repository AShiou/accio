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

package io.accio.main.pgcatalog.table;

import io.accio.base.metadata.SchemaTableName;
import io.accio.base.metadata.TableMetadata;

public final class PgCatalogTableUtils
{
    public static final String PG_CATALOG = "pg_catalog";
    public static final String DEFAULT_SCHEMA = "default";
    public static final String INFORMATION_SCHEMA = "information_schema";
    public static final String DEFAULT_AUTH = "accio";

    public static final String INTERNAL_LANGUAGE = "internal";

    private PgCatalogTableUtils() {}

    public static TableMetadata.Builder table(String tableName)
    {
        return table(DEFAULT_SCHEMA, tableName);
    }

    public static TableMetadata.Builder table(String schema, String tableName)
    {
        return TableMetadata.builder(new SchemaTableName(schema, tableName));
    }
}

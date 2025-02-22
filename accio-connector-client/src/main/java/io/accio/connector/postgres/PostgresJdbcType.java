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

package io.accio.connector.postgres;

import com.google.common.collect.ImmutableMap;
import io.accio.base.type.BigIntType;
import io.accio.base.type.BooleanType;
import io.accio.base.type.ByteaType;
import io.accio.base.type.DateType;
import io.accio.base.type.DoubleType;
import io.accio.base.type.IntegerType;
import io.accio.base.type.IntervalType;
import io.accio.base.type.NumericType;
import io.accio.base.type.OidType;
import io.accio.base.type.PGArray;
import io.accio.base.type.PGType;
import io.accio.base.type.RealType;
import io.accio.base.type.RegprocType;
import io.accio.base.type.SmallIntType;
import io.accio.base.type.TimestampType;
import io.accio.base.type.TimestampWithTimeZoneType;
import io.accio.base.type.VarcharType;

import java.util.Map;

public final class PostgresJdbcType
{
    private PostgresJdbcType() {}

    private static final Map<String, PGType<?>> jdbcTypeToPgTypeMap;

    static {
        ImmutableMap.Builder<String, PGType<?>> jdbcToPgBuilder = ImmutableMap.<String, PGType<?>>builder()
                .put("bool", BooleanType.BOOLEAN)
                .put("int2", SmallIntType.SMALLINT)
                .put("int4", IntegerType.INTEGER)
                .put("int8", BigIntType.BIGINT)
                .put("float4", RealType.REAL)
                .put("float8", DoubleType.DOUBLE)
                .put("regproc", RegprocType.REGPROC)
                .put("char", VarcharType.VARCHAR)
                .put("oid", OidType.OID_INSTANCE)
                .put("xid", IntegerType.INTEGER)
                .put("text", VarcharType.VARCHAR)
                .put("name", VarcharType.VARCHAR)
                .put("timestamptz", TimestampWithTimeZoneType.TIMESTAMP_WITH_TIMEZONE)
                .put("timestamp", TimestampType.TIMESTAMP)
                .put("numeric", NumericType.NUMERIC)
                .put("date", DateType.DATE)
                .put("bytea", ByteaType.BYTEA)
                .put("interval", IntervalType.INTERVAL)
                .put("_aclitem", PGArray.VARCHAR_ARRAY);

        PGArray.allArray().forEach(pgType -> jdbcToPgBuilder.put(pgType.typName(), pgType));

        jdbcTypeToPgTypeMap = jdbcToPgBuilder.build();
    }

    public static PGType<?> toPGType(String jdbcType)
    {
        return jdbcTypeToPgTypeMap.getOrDefault(jdbcType, VarcharType.VARCHAR);
    }
}

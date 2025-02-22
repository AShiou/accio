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

package io.accio.testing;

import io.accio.base.SessionContext;
import io.accio.base.dto.Manifest;
import org.intellij.lang.annotations.Language;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public abstract class AbstractTestFramework
{
    public static final SessionContext DEFAULT_SESSION_CONTEXT =
            SessionContext.builder().setCatalog("accio").setSchema("test").build();
    private Handle handle;

    public static Manifest.Builder withDefaultCatalogSchema()
    {
        return Manifest.builder()
                .setCatalog(DEFAULT_SESSION_CONTEXT.getCatalog().orElseThrow())
                .setSchema(DEFAULT_SESSION_CONTEXT.getSchema().orElseThrow());
    }

    @BeforeClass
    public void init()
    {
        handle = Jdbi.open("jdbc:h2:mem:test" + System.nanoTime() + ThreadLocalRandom.current().nextLong() + ";MODE=PostgreSQL;database_to_upper=false");
        prepareData();
    }

    @AfterClass(alwaysRun = true)
    public final void close()
    {
        try {
            handle.close();
        }
        finally {
            handle = null;
        }
    }

    protected void prepareData() {}

    protected List<List<Object>> query(@Language("SQL") String sql)
    {
        return handle.createQuery(sql)
                .map((resultSet, index, context) -> {
                    int count = resultSet.getMetaData().getColumnCount();
                    List<Object> row = new ArrayList<>(count);
                    for (int i = 1; i <= count; i++) {
                        Object value = resultSet.getObject(i);
                        row.add(value);
                    }
                    return row;
                })
                .list();
    }

    protected void exec(@Language("SQL") String sql)
    {
        handle.execute(sql);
    }
}

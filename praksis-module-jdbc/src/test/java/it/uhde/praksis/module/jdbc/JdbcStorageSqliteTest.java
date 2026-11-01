// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.jdbc;

import it.uhde.praksis.capability.EntityStorage;
import it.uhde.praksis.capability.StorageType;
import it.uhde.praksis.storage.testkit.EntityStorageContract;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** The JDBC storage in a SQLite file passes what every storage passes. */
class JdbcStorageSqliteTest extends EntityStorageContract {
  @Override
  protected EntityStorage open(Path dir, List<StorageType> types) throws Exception {
    JdbcStorage storage = new JdbcStorage();
    storage.configure(Map.of("url", "jdbc:sqlite:" + dir.resolve("db.sqlite")));
    storage.open(types);
    return storage;
  }
}

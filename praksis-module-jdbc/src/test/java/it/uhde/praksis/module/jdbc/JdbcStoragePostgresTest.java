// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.jdbc;

import it.uhde.praksis.capability.EntityStorage;
import it.uhde.praksis.capability.StorageType;
import it.uhde.praksis.storage.testkit.EntityStorageContract;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The JDBC storage in a PostgreSQL database passes what every storage passes. Runs only if {@code
 * PRAKSIS_TEST_PG_URL} names an empty database (for example {@code
 * jdbc:postgresql://localhost/praksis_test}), with {@code PRAKSIS_TEST_PG_USER} and {@code
 * PRAKSIS_TEST_PG_PASSWORD}, and the PostgreSQL driver is on the class path; the tables it makes
 * are dropped before each test.
 */
@EnabledIfEnvironmentVariable(named = "PRAKSIS_TEST_PG_URL", matches = "jdbc:postgresql:.*")
class JdbcStoragePostgresTest extends EntityStorageContract {
  @Override
  protected EntityStorage open(Path dir, List<StorageType> types) throws Exception {
    String url = System.getenv("PRAKSIS_TEST_PG_URL");
    Map<String, Object> settings = new java.util.HashMap<>();
    settings.put("url", url);
    settings.put("user", System.getenv("PRAKSIS_TEST_PG_USER"));
    JdbcStorage storage = new JdbcStorage();
    storage.configure(settings);
    storage.useCredentials(name -> System.getenv("PRAKSIS_TEST_PG_PASSWORD"));
    if (System.getenv("PRAKSIS_TEST_PG_PASSWORD") != null) {
      settings.put("password_credential", "pg");
      storage.configure(settings);
    }
    if (!dir.resolve(".dropped").toFile().exists()) {
      try (Connection c =
              DriverManager.getConnection(
                  url,
                  System.getenv("PRAKSIS_TEST_PG_USER"),
                  System.getenv("PRAKSIS_TEST_PG_PASSWORD"));
          Statement s = c.createStatement()) {
        s.execute("DROP TABLE IF EXISTS praksis_person, praksis_pet");
      }
      dir.resolve(".dropped").toFile().createNewFile();
    }
    storage.open(types);
    return storage;
  }
}

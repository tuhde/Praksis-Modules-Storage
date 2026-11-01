// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.capability.Query;
import it.uhde.praksis.capability.StorageException;
import it.uhde.praksis.capability.StorageType;
import it.uhde.praksis.capability.StoredEntity;
import it.uhde.praksis.capability.ValueType;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What is particular to the JDBC storage: the tables, the settings, the drivers and the rest. */
class JdbcStorageTest {
  @TempDir Path dir;

  private static final Instant T = Instant.parse("2026-11-01T10:00:00Z");

  private static final StorageType NOTE =
      new StorageType(
          "mail.note",
          List.of(
              new StorageType.Field("subject", ValueType.STRING, true, true),
              new StorageType.Field("size", ValueType.INT, false, false),
              new StorageType.Field("order", ValueType.STRING, false, false)));

  private String url() {
    return "jdbc:sqlite:" + dir.resolve("db.sqlite");
  }

  private JdbcStorage open(Map<String, Object> extra, StorageType... types) throws Exception {
    Map<String, Object> settings = new java.util.HashMap<>(Map.of("url", url()));
    settings.putAll(extra);
    JdbcStorage s = new JdbcStorage();
    s.configure(settings);
    s.open(List.of(types));
    return s;
  }

  private static StoredEntity note(String id, String subject) {
    return new StoredEntity(id, T, T, "rule:x", "user", Map.of("subject", subject, "size", 3L));
  }

  private List<String> sql(String query) throws Exception {
    List<String> out = new ArrayList<>();
    try (Connection c = DriverManager.getConnection(url());
        Statement s = c.createStatement();
        ResultSet r = s.executeQuery(query)) {
      while (r.next()) {
        out.add(r.getString(1));
      }
    }
    return out;
  }

  private void run(String statement) throws Exception {
    try (Connection c = DriverManager.getConnection(url());
        Statement s = c.createStatement()) {
      s.execute(statement);
    }
  }

  @Test
  void aTypeIsATableWithAColumnPerFieldAndAUniqueIndexAndOtherProgramsCanReadIt() throws Exception {
    JdbcStorage s = open(Map.of(), NOTE);
    s.insert("mail.note", note("a", "Hello"));
    // dots become underscores; the columns are those of the type; the time is milliseconds
    assertThat(sql("SELECT name FROM pragma_table_info('praksis_mail_note') ORDER BY cid"))
        .containsExactly(
            "id", "created", "updated", "created_by", "updated_by", "subject", "size", "order");
    assertThat(
            sql(
                "SELECT subject || '/' || size || '/' || created || '/' || created_by FROM praksis_mail_note"))
        .containsExactly("Hello/3/" + T.toEpochMilli() + "/rule:x");
    assertThat(sql("SELECT name FROM sqlite_master WHERE type = 'index' AND name LIKE '%_uq'"))
        .containsExactly("praksis_mail_note_subject_uq");
    // the database itself refuses a second entity with the same unique value
    assertThatThrownBy(() -> s.insert("mail.note", note("b", "Hello")))
        .isInstanceOf(StorageException.class)
        .hasMessageContaining("cannot insert");
    // a field called like a reserved word is no problem: names are quoted
    s.replace(
        "mail.note",
        new StoredEntity("a", T, T, "x", "y", Map.of("subject", "Hello", "order", "first")));
    assertThat(sql("SELECT \"order\" FROM praksis_mail_note")).containsExactly("first");
    s.close();
  }

  @Test
  void aChangeMadeInTheDatabaseIsSeenAtOnce() throws Exception {
    JdbcStorage s = open(Map.of(), NOTE);
    s.insert("mail.note", note("a", "Hello"));
    run("UPDATE praksis_mail_note SET subject = 'Changed' WHERE id = 'a'");
    assertThat(s.get("mail.note", "a").orElseThrow().fields()).containsEntry("subject", "Changed");
    run(
        "INSERT INTO praksis_mail_note (id, created, updated, created_by, updated_by, subject)"
            + " VALUES ('b', 0, 0, 'me', 'me', 'By hand')");
    assertThat(s.query("mail.note", Query.all()).total()).isEqualTo(2);
    assertThat(s.findUnique("mail.note", "subject", "By hand")).contains("b");
    s.close();
  }

  @Test
  void theTablePrefixIsChosen() throws Exception {
    JdbcStorage s = open(Map.of("table_prefix", "app_"), NOTE);
    s.insert("mail.note", note("a", "Hello"));
    assertThat(sql("SELECT count(*) FROM app_mail_note")).containsExactly("1");
    s.close();
  }

  @Test
  void anExistingTableIsCheckedAndGainsNewFields() throws Exception {
    run(
        "CREATE TABLE praksis_mail_note (id TEXT PRIMARY KEY, created BIGINT NOT NULL, updated BIGINT"
            + " NOT NULL, created_by TEXT NOT NULL, updated_by TEXT NOT NULL, subject TEXT)");
    JdbcStorage s = open(Map.of(), NOTE); // size and order are new fields
    s.insert("mail.note", note("a", "Hello"));
    assertThat(sql("SELECT name FROM pragma_table_info('praksis_mail_note') ORDER BY cid"))
        .contains("size", "order");
    s.close();

    run("ALTER TABLE praksis_mail_note ADD COLUMN stray TEXT");
    assertThatThrownBy(() -> open(Map.of(), NOTE))
        .isInstanceOf(StorageException.class)
        .hasMessageContaining("has a column 'stray' that is not a field of type 'mail.note'");

    run("CREATE TABLE praksis_pet (id TEXT PRIMARY KEY, name TEXT)");
    StorageType pet =
        new StorageType(
            "pet", List.of(new StorageType.Field("name", ValueType.STRING, false, false)));
    assertThatThrownBy(() -> open(Map.of(), pet))
        .hasMessageContaining("the table praksis_pet has no column 'created'");
  }

  @Test
  void aTransactionIsCommittedOrRolledBack() throws Exception {
    JdbcStorage s = open(Map.of(), NOTE);
    assertThatThrownBy(
            () ->
                s.atomically(
                    () -> {
                      s.insert("mail.note", note("a", "One"));
                      s.insert("mail.note", note("b", "Two"));
                      throw new StorageException("stop");
                    }))
        .hasMessage("stop");
    assertThat(s.query("mail.note", Query.all()).total()).isZero();
    s.atomically(
        () -> {
          s.insert("mail.note", note("a", "One"));
          return s.atomically(
              () -> {
                s.insert("mail.note", note("b", "Two")); // nested: part of the same transaction
                return null;
              });
        });
    assertThat(s.query("mail.note", Query.all()).total()).isEqualTo(2);
    s.close();
  }

  @Test
  void settingsAreChecked() {
    JdbcStorage s = new JdbcStorage();
    assertThatThrownBy(() -> s.configure(Map.of()))
        .isInstanceOf(CapabilityException.class)
        .hasMessageContaining("'url' is required");
    assertThatThrownBy(() -> s.configure(Map.of("url", "http://x")))
        .hasMessageContaining("'url' must start with jdbc:");
    assertThatThrownBy(() -> s.configure(Map.of("url", "jdbc:x", "colour", "red")))
        .hasMessageContaining("unknown setting 'colour'")
        .hasMessageContaining("password_credential");
    assertThatThrownBy(() -> s.configure(Map.of("url", "jdbc:x", "dialect", "oracle")))
        .hasMessageContaining("'dialect' must be one of [sqlite, h2, postgresql]");
    assertThatThrownBy(() -> s.configure(Map.of("url", "jdbc:x", "table_prefix", "No-Good")))
        .hasMessageContaining("'table_prefix' must be lower case");
    assertThatThrownBy(() -> s.configure(Map.of("url", "jdbc:x", "user", 5L)))
        .hasMessageContaining("'user' must be a text");
  }

  @Test
  void aMissingDriverIsSaidWithWhatWasFound() throws Exception {
    JdbcStorage s = new JdbcStorage();
    s.configure(Map.of("url", "jdbc:nothing://host/db"));
    assertThatThrownBy(() -> s.open(List.of(NOTE)))
        .isInstanceOf(StorageException.class)
        .hasMessageContaining("no driver accepts the url jdbc:nothing://host/db")
        .hasMessageContaining("org.sqlite.JDBC")
        .hasMessageContaining("put the jar of the driver into the module's archive");
    JdbcStorage named = new JdbcStorage();
    named.configure(Map.of("url", url(), "driver", "com.example.Nope"));
    assertThatThrownBy(() -> named.open(List.of(NOTE)))
        .hasMessageContaining("there is no driver 'com.example.Nope'");
    JdbcStorage right = new JdbcStorage();
    right.configure(Map.of("url", url(), "driver", "org.sqlite.JDBC"));
    right.open(List.of(NOTE));
    right.close();
  }

  @Test
  void aDatabaseWithoutADialectNeedsOne() throws Exception {
    // H2 reports itself as 'H2'; forcing another dialect works, an unknown product is refused by
    // name
    assertThat(Dialect.of("PostgreSQL")).contains(Dialect.POSTGRESQL);
    assertThat(Dialect.of("H2")).contains(Dialect.H2);
    assertThat(Dialect.of("SQLite")).contains(Dialect.SQLITE);
    assertThat(Dialect.of("Oracle")).isEmpty();
    assertThat(Dialect.POSTGRESQL.column(ValueType.BYTES)).isEqualTo("BYTEA");
    assertThat(Dialect.SQLITE.column(ValueType.TIMESTAMP)).isEqualTo("BIGINT");
  }

  @Test
  void offlineAllowsAFileOrMemoryDatabaseOnly() throws Exception {
    assertThat(JdbcStorage.local("jdbc:sqlite:/x/db")).isTrue();
    assertThat(JdbcStorage.local("jdbc:h2:file:/x/db")).isTrue();
    assertThat(JdbcStorage.local("jdbc:h2:mem:test")).isTrue();
    assertThat(JdbcStorage.local("jdbc:h2:~/test")).isTrue();
    assertThat(JdbcStorage.local("jdbc:h2:tcp://host/db")).isFalse();
    assertThat(JdbcStorage.local("jdbc:postgresql://localhost/db")).isFalse();

    JdbcStorage remote = new JdbcStorage();
    remote.useOffline();
    remote.configure(Map.of("url", "jdbc:postgresql://localhost/db"));
    assertThatThrownBy(() -> remote.open(List.of(NOTE)))
        .hasMessageContaining("offline = true")
        .hasMessageContaining("may be on another machine");

    JdbcStorage local = new JdbcStorage();
    local.useOffline();
    local.configure(Map.of("url", url()));
    local.open(List.of(NOTE));
    local.close();
  }

  @Test
  void thePasswordComesFromACredentialBinding() throws Exception {
    String url = "jdbc:h2:file:" + dir.resolve("secure") + ";DB_CLOSE_ON_EXIT=FALSE";
    try (Connection c = DriverManager.getConnection(url, "praksis", "s3cret")) {
      assertThat(c.isValid(1)).isTrue(); // makes the database with this user and password
    }
    List<String> asked = new ArrayList<>();
    JdbcStorage good = new JdbcStorage();
    good.configure(Map.of("url", url, "user", "praksis", "password_credential", "db-password"));
    good.useCredentials(
        name -> {
          asked.add(name);
          return "s3cret";
        });
    good.open(List.of(NOTE));
    good.insert("mail.note", note("a", "Hello"));
    good.close();
    assertThat(asked).containsExactly("db-password");

    JdbcStorage bad = new JdbcStorage();
    bad.configure(Map.of("url", url, "user", "praksis", "password_credential", "db-password"));
    bad.useCredentials(name -> "wrong");
    assertThatThrownBy(() -> bad.open(List.of(NOTE)))
        .isInstanceOf(StorageException.class)
        .hasMessageContaining("cannot connect to jdbc:h2:file:")
        .satisfies(
            e -> assertThat(e.getMessage()).doesNotContain("wrong").doesNotContain("s3cret"));

    JdbcStorage refused = new JdbcStorage();
    refused.configure(Map.of("url", url, "user", "praksis", "password_credential", "db-password"));
    refused.useCredentials(
        name -> {
          throw new CapabilityException("no binding named " + name, false);
        });
    assertThatThrownBy(() -> refused.open(List.of(NOTE)))
        .hasMessageContaining("cannot get the password: no binding named db-password");
  }

  @Test
  void jsonInColumnsIsExact() throws Exception {
    Map<String, Object> m = new java.util.LinkedHashMap<>();
    m.put("text", "quote \" backslash \\ newline \n tab \t control \u0001 ä☃😀");
    m.put("n", 12L);
    m.put("d", 1.5);
    m.put("b", true);
    m.put("none", null);
    m.put("list", List.of(1L, "x", List.of(), Map.of()));
    assertThat(Json.read(Json.write(m), ValueType.MAP)).isEqualTo(m);
    assertThat(Json.read("[1, 2.5, \"a\\u00e4\"]", ValueType.LIST))
        .isEqualTo(List.of(1L, 2.5, "aä"));
    for (String bad : List.of("[1", "{\"a\" 1}", "[1] x", "nope", "\"open")) {
      assertThatThrownBy(() -> Json.read(bad, ValueType.LIST)).isInstanceOf(StorageException.class);
    }
    assertThatThrownBy(() -> Json.read("{}", ValueType.LIST))
        .hasMessageContaining("a list was expected");
  }
}

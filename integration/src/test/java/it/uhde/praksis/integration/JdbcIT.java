// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The module {@code jdbc} installed from its archive: with the SQLite driver that the daemon has,
 * and with the jar of another driver (H2) put into the archive, as a person does it.
 */
class JdbcIT {
  private static final String ORDER =
      """
      format_version = 1
      id = "order"
      title = "Order"

      [[field]]
      name = "number"
      type = "string"
      required = true
      unique = true

      [[field]]
      name = "total"
      type = "double"

      [[field]]
      name = "paid"
      type = "bool"

      [[field]]
      name = "placed"
      type = "timestamp"
      """;

  private static String id(String output) {
    Matcher m = Pattern.compile("(?m)^id: (\\S+)$").matcher(output);
    assertThat(m.find()).as(output).isTrue();
    return m.group(1);
  }

  private static List<String> rows(String url, String sql) throws Exception {
    List<String> out = new java.util.ArrayList<>();
    try (Connection c = DriverManager.getConnection(url);
        Statement s = c.createStatement();
        ResultSet r = s.executeQuery(sql)) {
      while (r.next()) {
        out.add(r.getString(1));
      }
    }
    return out;
  }

  @Test
  void entitiesLiveInATableOfTheDatabaseOfTheDaemonsOwnDriver() throws Exception {
    try (Harness h = new Harness("jdbc")) {
      h.write("ruleset/entities/order.toml", ORDER);
      Path db = h.home.resolve("shop.sqlite");
      h.write(
          "praksis.toml",
          "format_version = 1\n\n[entities.storage.shop]\nbackend = \"jdbc\"\nurl = \"jdbc:sqlite:"
              + db
              + "\"\n\n[entities.bind]\norder = \"shop\"\n");
      Process daemon = h.startDaemon();

      Harness.CliResult created =
          h.cli(
              "entities",
              "create",
              "order",
              "--set",
              "number=A-1",
              "--set",
              "total=19.5",
              "--set",
              "paid=true",
              "--set",
              "placed=2026-11-01T10:00:00Z");
      assertThat(created.exit()).as(created.output() + h.daemonLog()).isZero();
      String id = id(created.output());

      // the data is in a table that any program reads
      String url = "jdbc:sqlite:" + db;
      assertThat(
              rows(
                  url,
                  "SELECT number || '|' || total || '|' || paid || '|' || placed FROM praksis_order"))
          .containsExactly(
              "A-1|19.5|1|" + java.time.Instant.parse("2026-11-01T10:00:00Z").toEpochMilli());
      assertThat(rows(url, "SELECT created_by FROM praksis_order")).containsExactly("user");

      // the database enforces and the daemon reports the unique value
      Harness.CliResult clash = h.cli("entities", "create", "order", "--set", "number=A-1");
      assertThat(clash.exit()).isEqualTo(1);
      assertThat(clash.output()).contains("conflict: another 'order' already has number = A-1");

      // a row inserted by another program is an entity
      try (Connection c = DriverManager.getConnection(url);
          Statement s = c.createStatement()) {
        s.execute(
            "INSERT INTO praksis_order (id, created, updated, created_by, updated_by, number, total)"
                + " VALUES ('x1', 0, 0, 'erp', 'erp', 'B-2', 5.0)");
      }
      assertThat(h.cli("entities", "list", "order", "--sort", "number").output())
          .contains(id + "  number=A-1")
          .contains("x1  number=B-2  total=5.0")
          .contains("2 of 2");
      assertThat(
              h.cli(
                      "entities",
                      "list",
                      "order",
                      "--filter",
                      "{\"op\":\"range\",\"field\":\"total\",\"gt\":10}")
                  .output())
          .contains(id)
          .contains("1 of 1");

      h.kill(daemon);
      Process again = h.startDaemon();
      assertThat(h.cli("entities", "show", "order", id).output())
          .contains("number: A-1")
          .contains("paid: true");
      assertThat(h.cli("entities", "delete", "order", id).exit()).isZero();
      assertThat(rows(url, "SELECT count(*) FROM praksis_order")).containsExactly("1");
      h.stop(again);
    }
  }

  @Test
  void aDriverJarInTheArchiveIsFoundAndThePasswordComesFromACredentialBinding() throws Exception {
    Path h2 = Path.of(System.getProperty("h2.jar"));
    try (Harness h = new Harness(Map.of("jdbc", List.of(h2)), "jdbc")) {
      h.write("ruleset/entities/order.toml", ORDER);
      String dbFile = h.home.resolve("shop-h2").toString();
      String url = "jdbc:h2:file:" + dbFile;
      // make the database with a user and a password, as a database administrator would
      try (Connection c = DriverManager.getConnection(url, "shop", "pa55")) {
        assertThat(c.isValid(1)).isTrue();
      }
      // the password is a secret that a credential binding prints; the storage may ask for it
      h.write(
          "policy.toml",
          """
          format_version = 1

          [credential."shop-db"]
          command = ["/bin/echo", "pa55"]
          capabilities = ["entity-storage:shop"]
          """);
      h.write(
          "praksis.toml",
          "format_version = 1\n\n[entities.storage.shop]\nbackend = \"jdbc\"\nurl = \""
              + url
              + "\"\nuser = \"shop\"\npassword_credential = \"shop-db\"\n\n[entities.bind]\norder = \"shop\"\n");
      Process daemon = h.startDaemon();
      Harness.CliResult created =
          h.cli("entities", "create", "order", "--set", "number=H-1", "--set", "total=3");
      assertThat(created.exit()).as(created.output() + h.daemonLog()).isZero();
      assertThat(h.cli("audit").output()).doesNotContain("pa55");
      h.stop(daemon);
      try (Connection c = DriverManager.getConnection(url, "shop", "pa55");
          Statement s = c.createStatement();
          ResultSet r = s.executeQuery("SELECT \"number\" FROM \"praksis_order\"")) {
        assertThat(r.next()).isTrue();
        assertThat(r.getString(1)).isEqualTo("H-1");
      }
    }
  }

  @Test
  void aRemoteDatabaseIsRefusedWhileOfflineAndAFileDatabaseIsNot() throws Exception {
    try (Harness h = new Harness("jdbc")) {
      h.write("ruleset/entities/order.toml", ORDER);
      h.write(
          "praksis.toml",
          "format_version = 1\noffline = true\n\n[entities.storage.shop]\nbackend = \"jdbc\"\n"
              + "url = \"jdbc:postgresql://db.example.org/shop\"\n\n[entities.bind]\norder = \"shop\"\n");
      Harness.CliResult remote = h.cli("start");
      assertThat(remote.exit()).isNotZero();
      assertThat(remote.output()).contains("offline = true").contains("may be on another machine");

      h.write(
          "praksis.toml",
          "format_version = 1\noffline = true\n\n[entities.storage.shop]\nbackend = \"jdbc\"\n"
              + "url = \"jdbc:sqlite:"
              + h.home.resolve("local.sqlite")
              + "\"\n\n[entities.bind]\norder = \"shop\"\n");
      Process daemon = h.startDaemon();
      assertThat(h.cli("entities", "create", "order", "--set", "number=O-1").exit()).isZero();
      h.stop(daemon);
      assertThat(Files.exists(h.home.resolve("local.sqlite"))).isTrue();
    }
  }
}

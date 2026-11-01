// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The modules {@code csv-file} and {@code json-file} installed side by side from their archives,
 * two types bound to one each, and the daemon and the CLI run from the runtime image of the core.
 */
class CsvFileIT {
  private static final String CONTACT =
      """
      format_version = 1
      id = "contact"
      title = "Contact"

      [[field]]
      name = "name"
      type = "string"
      required = true

      [[field]]
      name = "email"
      type = "string"
      unique = true
      sensitive = true

      [[field]]
      name = "age"
      type = "int"
      """;

  private static final String NOTE =
      """
      format_version = 1
      id = "note"
      title = "Note"

      [[field]]
      name = "text"
      type = "string"
      """;

  private static String id(String output) {
    Matcher m = Pattern.compile("(?m)^id: (\\S+)$").matcher(output);
    assertThat(m.find()).as(output).isTrue();
    return m.group(1);
  }

  @Test
  void contactsLiveInACsvFileANoteInAJsonFileAndASpreadsheetCanEditTheCsv() throws Exception {
    try (Harness h = new Harness("json-file", "csv-file")) {
      h.write("ruleset/entities/contact.toml", CONTACT);
      h.write("ruleset/entities/note.toml", NOTE);
      h.write(
          "praksis.toml",
          """
          format_version = 1

          [entities.storage.book]
          backend = "csv-file"
          delimiter = ";"

          [entities.storage.notes]
          backend = "json-file"

          [entities.bind]
          contact = "book"
          note = "notes"
          """);
      Process daemon = h.startDaemon();

      Harness.CliResult mei =
          h.cli(
              "entities",
              "create",
              "contact",
              "--set",
              "name=Mei; \"Ling\"",
              "--set",
              "email=mei@example.org",
              "--set",
              "age=41");
      assertThat(mei.exit()).as(mei.output() + h.daemonLog()).isZero();
      String meiId = id(mei.output());
      assertThat(h.cli("entities", "create", "contact", "--set", "name==SUM(A1)").exit()).isZero();
      assertThat(h.cli("entities", "create", "note", "--set", "text=hello").exit()).isZero();

      Path csv = h.home.resolve("data/storage/book/contact.csv");
      String text = Files.readString(csv);
      assertThat(text)
          .startsWith("id;created;updated;created_by;updated_by;name;email;age\n")
          .contains(";\"Mei; \"\"Ling\"\"\";mei@example.org;41\n")
          .contains(";'=SUM(A1);;\n"); // a formula is written safe
      assertThat(h.home.resolve("data/storage/notes/note.json")).exists();

      // the sensitive e-mail is left out of what is shown and is revealed only on request
      assertThat(h.cli("entities", "show", "contact", meiId).output())
          .doesNotContain("mei@example.org");
      assertThat(h.cli("entities", "show", "contact", meiId, "--reveal", "email").output())
          .contains("email: mei@example.org  (revealed)");

      // a person adds a row in a spreadsheet; Praksis sees it without a restart
      Files.writeString(
          csv,
          text
              + "manual-1;2026-11-02T08:00:00Z;2026-11-02T08:00:00Z;user;user;Ole;ole@example.org;65\n");
      Harness.CliResult list = h.cli("entities", "list", "contact", "--sort", "name");
      assertThat(list.output()).contains("manual-1  name=Ole  age=65").contains("3 of 3");
      // and the unique value of that row is known to Praksis
      Harness.CliResult clash =
          h.cli(
              "entities", "create", "contact", "--set", "name=X", "--set", "email=ole@example.org");
      assertThat(clash.exit()).isEqualTo(1);
      assertThat(clash.output()).contains("conflict: another 'contact' already has email");
      assertThat(clash.output()).doesNotContain("ole@example.org");

      // a kill and a restart lose nothing
      h.kill(daemon);
      Process again = h.startDaemon();
      assertThat(h.cli("entities", "show", "contact", meiId).output())
          .contains("name: Mei; \"Ling\"");
      assertThat(h.cli("entities", "update", "contact", "manual-1", "--set", "age=66").output())
          .contains("age: 66");
      assertThat(Files.readString(csv)).contains("manual-1;").contains(";66\n");
      h.stop(again);
    }
  }

  @Test
  void aFileThatIsNotRightStopsTheStartWithTheRow() throws Exception {
    try (Harness h = new Harness("csv-file")) {
      h.write("ruleset/entities/contact.toml", CONTACT);
      h.write(
          "praksis.toml",
          """
          format_version = 1

          [entities.storage.book]
          backend = "csv-file"
          delimiter = "\\t"
          """
                  .replace("\\t", ",")
              + "\n[entities.bind]\ncontact = \"book\"\n");
      h.write(
          "data/storage/book/contact.csv",
          "id,created,updated,created_by,updated_by,name\n"
              + "a,2026-11-01T10:00:00Z,2026-11-01T10:00:00Z,user,user,Mei\n"
              + "a,2026-11-01T10:00:00Z,2026-11-01T10:00:00Z,user,user,Ole\n");
      Harness.CliResult start = h.cli("start");
      assertThat(start.exit()).isNotZero();
      assertThat(start.output()).contains("contact.csv").contains("the id a is used twice");
    }
  }
}

// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Moving the entities of a type from one storage to another with export and import: from the
 * database of the daemon to a JSON file, and on to a CSV file. The ids, times and authors stay.
 */
class MoveIT {
  private static final String NOTE =
      """
      format_version = 1
      id = "note"
      title = "Note"

      [[field]]
      name = "text"
      type = "string"
      required = true

      [[field]]
      name = "secret"
      type = "string"
      sensitive = true
      """;

  private static String id(String output) {
    Matcher m = Pattern.compile("(?m)^id: (\\S+)$").matcher(output);
    assertThat(m.find()).as(output).isTrue();
    return m.group(1);
  }

  private static String bind(String backend, String extra) {
    return "format_version = 1\n\n[entities.storage.notes]\nbackend = \""
        + backend
        + "\"\n"
        + extra
        + "\n[entities.bind]\nnote = \"notes\"\n";
  }

  @Test
  void aTypeMovesFromTheDaemonsDatabaseToAJsonFileAndOnToACsvFile() throws Exception {
    try (Harness h = new Harness("json-file", "csv-file")) {
      h.write("ruleset/entities/note.toml", NOTE);
      // no storage is bound yet: the notes are in the daemon's database
      h.write("praksis.toml", "format_version = 1\nenabled_modules = [\"json-file\"]\n");
      Process daemon = h.startDaemon();
      String a =
          id(
              h.cli("entities", "create", "note", "--set", "text=first", "--set", "secret=s3cret")
                  .output());
      String b =
          id(h.cli("entities", "create", "note", "--set", "text=second, with a comma").output());
      Path dump = h.home.resolve("notes.json");
      assertThat(h.cli("entities", "export", "note", "--out", dump.toString()).exit()).isZero();
      h.stop(daemon);

      // now the notes are kept in a JSON file: the new place starts empty, the import fills it
      h.write("praksis.toml", bind("json-file", ""));
      daemon = h.startDaemon();
      assertThat(h.cli("entities", "list", "note").output()).contains("0 of 0");
      Harness.CliResult imported = h.cli("entities", "import", "note", "--in", dump.toString());
      assertThat(imported.exit()).as(imported.output() + h.daemonLog()).isZero();
      assertThat(imported.output()).contains("Imported: 2 new, 0 replaced");
      Path json = h.home.resolve("data/storage/notes/note.json");
      assertThat(Files.readString(json))
          .contains("\"id\" : \"" + a + "\"")
          .contains("\"id\" : \"" + b + "\"")
          .contains("\"secret\" : \"s3cret\"");
      assertThat(h.cli("entities", "show", "note", a, "--reveal", "secret").output())
          .contains("text: first")
          .contains("secret: s3cret  (revealed)");
      Path dump2 = h.home.resolve("notes2.json");
      assertThat(h.cli("entities", "export", "note", "--out", dump2.toString()).exit()).isZero();
      h.stop(daemon);

      // and on to a CSV file
      h.write("praksis.toml", bind("csv-file", ""));
      daemon = h.startDaemon();
      assertThat(h.cli("entities", "import", "note", "--in", dump2.toString()).exit()).isZero();
      List<String> csv = Files.readAllLines(h.home.resolve("data/storage/notes/note.csv"));
      assertThat(csv.get(0)).isEqualTo("id,created,updated,created_by,updated_by,text,secret");
      assertThat(csv)
          .anyMatch(l -> l.startsWith(a + ","))
          .anyMatch(l -> l.contains("\"second, with a comma\""));
      assertThat(h.cli("entities", "show", "note", b).output())
          .contains("id: " + b)
          .contains("text: second, with a comma")
          .contains("created_by: user");
      h.stop(daemon);
    }
  }
}

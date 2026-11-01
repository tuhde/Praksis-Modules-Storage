// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The module {@code json-file} installed like a person installs it, a type bound to it in {@code
 * praksis.toml}, and the daemon and the CLI run from the runtime image of the core.
 */
class JsonFileIT {
  private static final String NOTE =
      """
      format_version = 1
      id = "note"
      title = "Note"

      [[field]]
      name = "subject"
      type = "string"
      required = true
      unique = true

      [[field]]
      name = "pinned"
      type = "bool"
      """;

  private static final String LOG_TYPE =
      """
      format_version = 1
      id = "journal"
      title = "Journal"

      [[field]]
      name = "text"
      type = "string"
      required = true
      """;

  private static String id(String output) {
    Matcher m = Pattern.compile("(?m)^id: (\\S+)$").matcher(output);
    assertThat(m.find()).as(output).isTrue();
    return m.group(1);
  }

  @Test
  void entitiesLiveInAJsonFileAndSurviveARestartAndAKill() throws Exception {
    try (Harness h = new Harness("json-file")) {
      h.write("ruleset/entities/note.toml", NOTE);
      h.write("ruleset/entities/journal.toml", LOG_TYPE);
      // a rule that writes a journal entry (in the daemon's own database) for every note
      h.write(
          "ruleset/rules/note.journal.toml",
          """
          id = "note.journal"
          effect = "local-write"
          needs = ["entity.create"]

          [trigger]
          kind = "event"
          type = "entity.created"
          filter = "event.data.type == 'note'"

          [[action]]
          id = "entry"
          capability = "entity.create"
          args = { type = "journal", values = { cel = "{'text': 'new note ' + event.data.id}" } }
          """);
      h.write(
          "policy.toml",
          """
          format_version = 1

          [[grant]]
          id = "journal"
          layer = "ruleset"
          capability = "entity.create"
          [grant.args]
          type = { equals = "journal" }
          """);
      // only the note is bound; the journal stays in the database of the daemon. The module is
      // enabled because a storage it provides is configured
      h.write(
          "praksis.toml",
          """
          format_version = 1

          [entities.storage.notes]
          backend = "json-file"
          pretty = true

          [entities.bind]
          note = "notes"
          """);
      Process daemon = h.startDaemon();

      Harness.CliResult created =
          h.cli("entities", "create", "note", "--set", "subject=Buy milk", "--set", "pinned=true");
      assertThat(created.exit()).as(created.output() + h.daemonLog()).isZero();
      String id = id(created.output());

      // the file is where the module's directory is, readable, and holds the entity
      Path file = h.home.resolve("data/storage/notes/note.json");
      assertThat(file).exists();
      assertThat(Files.readString(file))
          .contains("\"id\" : \"" + id + "\"")
          .contains("\"subject\" : \"Buy milk\"")
          .contains("\"pinned\" : true")
          .contains("\"created_by\" : \"user\"");
      assertThat(h.home.resolve("data/storage/notes/journal.json")).doesNotExist();

      // the event of the change reached the rule, which wrote to the other storage
      Harness.await(
          "the journal entry",
          () -> h.cli("entities", "list", "journal").output().contains("text=new note " + id));

      // the unique value is kept across the storage
      Harness.CliResult clash = h.cli("entities", "create", "note", "--set", "subject=Buy milk");
      assertThat(clash.exit()).isEqualTo(1);
      assertThat(clash.output())
          .contains("conflict: another 'note' already has subject = Buy milk");

      // a hand edit of the file is seen without a restart
      Files.writeString(file, Files.readString(file).replace("Buy milk", "Buy oat milk"));
      assertThat(h.cli("entities", "list", "note").output()).contains("subject=Buy oat milk");
      assertThat(h.cli("entities", "update", "note", id, "--set", "pinned=false").output())
          .contains("pinned: false");

      // a kill without a chance to clean up loses nothing that was answered
      h.kill(daemon);
      Process again = h.startDaemon();
      assertThat(h.cli("entities", "show", "note", id).output())
          .contains("subject: Buy oat milk")
          .contains("pinned: false");
      assertThat(h.cli("entities", "delete", "note", id).exit()).isZero();
      assertThat(Files.readString(file)).doesNotContain(id);
      h.stop(again);
    }
  }

  @Test
  void aMissingOrBrokenStorageStopsTheStartAndSaysWhy() throws Exception {
    try (Harness h = new Harness("json-file")) {
      h.write("ruleset/entities/note.toml", NOTE);
      h.write(
          "praksis.toml",
          """
          format_version = 1

          [entities.storage.notes]
          backend = "json-file"
          colour = "red"

          [entities.bind]
          note = "notes"
          """);
      Harness.CliResult start = h.cli("start");
      assertThat(start.exit()).isNotZero();
      assertThat(start.output())
          .contains("[entities.storage.notes]")
          .contains("unknown setting 'colour'");

      h.write(
          "praksis.toml",
          """
          format_version = 1
          enabled_modules = ["json-file"]

          [entities.storage.notes]
          backend = "csv-file"

          [entities.bind]
          note = "notes"
          """);
      Harness.CliResult missing = h.cli("start");
      assertThat(missing.exit()).isNotZero();
      assertThat(missing.output())
          .contains("no module provides the entity storage 'csv-file'")
          .contains("provided: [json-file]");

      // a file that is not right stops the start with the file named
      h.write(
          "praksis.toml",
          "format_version = 1\n[entities.storage.notes]\nbackend = \"json-file\"\n"
              + "[entities.bind]\nnote = \"notes\"\n");
      h.write("data/storage/notes/note.json", "{\"format\":1,\"type\":\"memo\",\"entities\":[]}");
      Harness.CliResult broken = h.cli("start");
      assertThat(broken.exit()).isNotZero();
      assertThat(broken.output()).contains("note.json").contains("\"type\" must be \"note\"");
    }
  }
}

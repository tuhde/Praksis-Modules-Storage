// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.jsonfile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.capability.EntityStorage;
import it.uhde.praksis.capability.Query;
import it.uhde.praksis.capability.StorageException;
import it.uhde.praksis.capability.StorageType;
import it.uhde.praksis.capability.StoredEntity;
import it.uhde.praksis.storage.testkit.EntityStorageContract;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The JSON file storage passes what every storage passes, and keeps its files as promised. */
class JsonFileStorageTest extends EntityStorageContract {
  @Override
  protected EntityStorage open(Path dir, List<StorageType> types) throws Exception {
    JsonFileStorage storage = new JsonFileStorage();
    storage.configure(Map.of());
    storage.useDataDirectory(dir.resolve("data"));
    storage.open(types);
    return storage;
  }

  private static StoredEntity entity(String id, String name) {
    Instant t = Instant.parse("2026-11-01T10:00:00Z");
    return new StoredEntity(id, t, t, "user", "user", Map.of("name", name));
  }

  @Test
  void aTypeIsOneReadableFile() throws Exception {
    EntityStorage s = storage();
    s.insert("person", entity("a", "Mei"));
    Path file = dir.resolve("data/person.json");
    String text = Files.readString(file);
    assertThat(text)
        .contains("\"format\" : 1")
        .contains("\"type\" : \"person\"")
        .contains("\"id\" : \"a\"")
        .contains("\"created\" : \"2026-11-01T10:00:00Z\"")
        .contains("\"name\" : \"Mei\"");
    assertThat(dir.resolve("data/pet.json")).doesNotExist(); // nothing written before a change
    assertThat(dir.resolve("data/person.json.tmp")).doesNotExist();
    assertThat(java.nio.file.Files.getPosixFilePermissions(file))
        .containsExactlyInAnyOrder(
            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
  }

  @Test
  void aFileChangedByHandIsReadAgainBeforeTheNextOperation() throws Exception {
    EntityStorage s = storage();
    s.insert("person", entity("a", "Mei"));
    Path file = dir.resolve("data/person.json");
    Files.writeString(
        file,
        Files.readString(file)
            .replace(
                "\"entities\" : [ {",
                "\"entities\" : [ { \"id\" : \"b\", \"created\" : \"2026-11-02T00:00:00Z\","
                    + " \"updated\" : \"2026-11-02T00:00:00Z\", \"created_by\" : \"user\","
                    + " \"updated_by\" : \"user\", \"fields\" : { \"name\" : \"Ole\" } }, {"));
    assertThat(s.get("person", "b").orElseThrow().fields()).containsEntry("name", "Ole");
    assertThat(s.query("person", Query.all()).total()).isEqualTo(2);
    s.delete("person", "a");
    assertThat(Files.readString(file)).contains("\"id\" : \"b\"").doesNotContain("\"id\" : \"a\"");
  }

  @Test
  void aFileThatIsNotRightIsRefusedWithItsNameAndTheEntity() throws Exception {
    Path data = Files.createDirectories(dir.resolve("data"));
    Path file = data.resolve("person.json");
    String head = "{\"format\":1,\"type\":\"person\",\"entities\":[";
    String entity =
        "{\"id\":\"a\",\"created\":\"2026-11-01T10:00:00Z\",\"updated\":\"2026-11-01T10:00:00Z\","
            + "\"created_by\":\"user\",\"updated_by\":\"user\",\"fields\":{FIELDS}}";
    record Bad(String file, String message) {}
    for (Bad bad :
        List.of(
            new Bad("not json", "is not valid JSON"),
            new Bad("[]", "must hold one JSON object"),
            new Bad("{\"format\":2,\"type\":\"person\",\"entities\":[]}", "\"format\" must be 1"),
            new Bad(
                "{\"format\":1,\"type\":\"pet\",\"entities\":[]}", "\"type\" must be \"person\""),
            new Bad(
                head + entity.replace("{FIELDS}", "{\"age\":\"old\"}") + "]}",
                "entity 1: field 'age' must be a whole number"),
            new Bad(
                head + entity.replace("{FIELDS}", "{\"colour\":\"red\"}") + "]}",
                "the field 'colour' is not declared"),
            new Bad(
                head
                    + entity.replace("{FIELDS}", "{}")
                    + ","
                    + entity.replace("{FIELDS}", "{}")
                    + "]}",
                "the id a is used twice"),
            new Bad(
                head
                    + entity.replace("2026-11-01T10:00:00Z", "yesterday").replace("{FIELDS}", "{}")
                    + "]}",
                "\"created\" must be a time"))) {
      Files.writeString(file, bad.file());
      JsonFileStorage s = new JsonFileStorage();
      s.configure(Map.of());
      s.useDataDirectory(data);
      assertThatThrownBy(() -> s.open(List.of(PERSON)))
          .isInstanceOf(StorageException.class)
          .hasMessageContaining(file.toString())
          .hasMessageContaining(bad.message());
    }
  }

  @Test
  void settingsAreCheckedAndTheDirectoryCanBeChosen() throws Exception {
    JsonFileStorage s = new JsonFileStorage();
    assertThatThrownBy(() -> s.configure(Map.of("colour", "red")))
        .isInstanceOf(CapabilityException.class)
        .hasMessageContaining("unknown setting 'colour'");
    assertThatThrownBy(() -> s.configure(Map.of("directory", 5L)))
        .hasMessageContaining("'directory' must be a path");
    assertThatThrownBy(() -> s.configure(Map.of("pretty", "yes")))
        .hasMessageContaining("'pretty' must be true or false");
    assertThatThrownBy(() -> new JsonFileStorage().open(List.of(PERSON)))
        .hasMessageContaining("no directory");

    Path chosen = dir.resolve("elsewhere");
    JsonFileStorage t = new JsonFileStorage();
    t.configure(Map.of("directory", chosen.toString(), "pretty", false));
    t.useDataDirectory(dir.resolve("data"));
    t.open(List.of(PERSON));
    t.insert("person", entity("a", "Mei"));
    assertThat(Files.readString(chosen.resolve("person.json"))).startsWith("{\"format\":1");
    assertThat(dir.resolve("data/person.json")).doesNotExist();

    // a relative directory is below the module's own directory
    JsonFileStorage r = new JsonFileStorage();
    r.configure(Map.of("directory", "sub"));
    r.useDataDirectory(dir.resolve("data"));
    r.open(List.of(PERSON));
    r.insert("person", entity("a", "Mei"));
    assertThat(dir.resolve("data/sub/person.json")).exists();
  }

  @Test
  void anIdInUseOrMissingIsRefused() throws Exception {
    EntityStorage s = storage();
    s.insert("person", entity("a", "Mei"));
    assertThatThrownBy(() -> s.insert("person", entity("a", "Other")))
        .hasMessageContaining("in use");
    assertThatThrownBy(() -> s.replace("person", entity("zzz", "Other")))
        .hasMessageContaining("there is no person zzz");
    assertThatThrownBy(() -> s.get("nope", "a")).hasMessageContaining("was not opened for type");
    assertThat(s.get("person", "a").orElseThrow().fields()).containsEntry("name", "Mei");
  }

  @Test
  void aFailedWriteLeavesTheEntitiesAsTheyWere() throws Exception {
    EntityStorage s = storage();
    s.insert("person", entity("a", "Mei"));
    Path data = dir.resolve("data");
    // a directory where the temporary file must go makes the write fail
    Files.createDirectory(data.resolve("person.json.tmp"));
    Files.createFile(data.resolve("person.json.tmp").resolve("blocker"));
    assertThatThrownBy(() -> s.insert("person", entity("b", "Ole")))
        .isInstanceOf(StorageException.class)
        .hasMessageContaining("cannot write");
    assertThat(s.get("person", "b")).isEmpty();
    assertThat(s.query("person", Query.all()).total()).isEqualTo(1);
    assertThat(IOException.class).isNotNull();
  }
}

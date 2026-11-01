// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.csvfile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.capability.EntityStorage;
import it.uhde.praksis.capability.Query;
import it.uhde.praksis.capability.StorageException;
import it.uhde.praksis.capability.StorageType;
import it.uhde.praksis.capability.StoredEntity;
import it.uhde.praksis.storage.testkit.EntityStorageContract;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The CSV file storage passes what every storage passes, and keeps its files as promised. */
class CsvFileStorageTest extends EntityStorageContract {
  @Override
  protected EntityStorage open(Path dir, List<StorageType> types) throws Exception {
    CsvFileStorage storage = new CsvFileStorage();
    storage.configure(Map.of());
    storage.useDataDirectory(dir.resolve("data"));
    storage.open(types);
    return storage;
  }

  private static final Instant T = Instant.parse("2026-11-01T10:00:00Z");

  private static StoredEntity entity(String id, Map<String, Object> fields) {
    return new StoredEntity(id, T, T, "user", "user", fields);
  }

  private CsvFileStorage with(Map<String, Object> settings) throws Exception {
    CsvFileStorage s = new CsvFileStorage();
    s.configure(settings);
    s.useDataDirectory(dir.resolve("data"));
    s.open(List.of(PERSON));
    return s;
  }

  @Test
  void aTypeIsOneFileThatASpreadsheetOpens() throws Exception {
    EntityStorage s = storage();
    s.insert(
        "person",
        entity(
            "a",
            new java.util.LinkedHashMap<>(
                Map.of("name", "Mei, \"M\"", "age", 41L, "vip", true, "note", ""))));
    s.insert("person", entity("b", Map.of("name", "Ole")));
    Path file = dir.resolve("data/person.csv");
    List<String> lines = Files.readAllLines(file);
    assertThat(lines.get(0))
        .isEqualTo(
            "id,created,updated,created_by,updated_by,name,age,score,vip,met,photo,tags,extra,note");
    assertThat(lines.get(1))
        .isEqualTo(
            "a,2026-11-01T10:00:00Z,2026-11-01T10:00:00Z,user,user,\"Mei, \"\"M\"\"\",41,,true,,,,,\"\"");
    assertThat(lines.get(2))
        .isEqualTo("b,2026-11-01T10:00:00Z,2026-11-01T10:00:00Z,user,user,Ole,,,,,,,,");
    assertThat(dir.resolve("data/person.csv.tmp")).doesNotExist();
  }

  @Test
  void aTextThatASpreadsheetWouldRunAsAFormulaIsWrittenSafe() throws Exception {
    EntityStorage s = storage();
    s.insert("person", entity("a", Map.of("name", "=HYPERLINK(\"http://evil\")", "note", "@cmd")));
    String text = Files.readString(dir.resolve("data/person.csv"));
    assertThat(text).contains("'=HYPERLINK(").contains(",'@cmd");
    assertThat(s.get("person", "a").orElseThrow().fields())
        .containsEntry("name", "=HYPERLINK(\"http://evil\")")
        .containsEntry("note", "@cmd");

    // the setting turns it off
    CsvFileStorage raw = new CsvFileStorage();
    raw.configure(Map.of("escape_formulas", false));
    raw.useDataDirectory(dir.resolve("raw"));
    raw.open(List.of(PERSON));
    raw.insert("person", entity("a", Map.of("name", "=1+1")));
    assertThat(Files.readString(dir.resolve("raw/person.csv")))
        .contains(",=1+1,")
        .doesNotContain("'=");
  }

  @Test
  void aDelimiterOtherThanTheCommaCanBeChosen() throws Exception {
    CsvFileStorage s = with(Map.of("delimiter", ";"));
    s.insert("person", entity("a", Map.of("name", "A;B", "score", 1.5)));
    String text = Files.readString(dir.resolve("data/person.csv"));
    assertThat(text).startsWith("id;created;updated;created_by;updated_by;name;");
    assertThat(text).contains(";\"A;B\";;1.5;");
    assertThat(s.get("person", "a").orElseThrow().fields()).containsEntry("name", "A;B");
  }

  @Test
  void aSpreadsheetsFileIsRead() throws Exception {
    // a byte order mark, other line ends, another column order, an upper case bool, a missing
    // column
    Path data = Files.createDirectories(dir.resolve("data"));
    String csv =
        "﻿name;id;created;updated;updated_by;created_by;vip\r\n"
            + "Mei;a;2026-11-01T10:00:00Z;2026-11-01T10:00:00Z;user;user;TRUE\r\n"
            + "\r\n"
            + "Ole;b;2026-11-01T10:00:00Z;2026-11-01T10:00:00Z;user;user;\r";
    Files.write(data.resolve("person.csv"), csv.getBytes(StandardCharsets.UTF_8));
    CsvFileStorage s = with(Map.of("delimiter", ";"));
    assertThat(s.get("person", "a").orElseThrow().fields())
        .containsEntry("name", "Mei")
        .containsEntry("vip", true)
        .doesNotContainKey("age");
    assertThat(s.get("person", "b").orElseThrow().fields()).doesNotContainKey("vip");
    // the next write puts every column in its place
    s.insert("person", entity("c", Map.of("name", "Ida")));
    assertThat(Files.readAllLines(data.resolve("person.csv")).get(0))
        .isEqualTo(
            "id;created;updated;created_by;updated_by;name;age;score;vip;met;photo;tags;extra;note");
    assertThat(s.query("person", Query.all()).total()).isEqualTo(3);
  }

  @Test
  void aFileChangedByHandIsReadAgain() throws Exception {
    EntityStorage s = storage();
    s.insert("person", entity("a", Map.of("name", "Mei")));
    Path file = dir.resolve("data/person.csv");
    Files.writeString(file, Files.readString(file).replace("Mei", "Mei-Ling"));
    assertThat(s.get("person", "a").orElseThrow().fields()).containsEntry("name", "Mei-Ling");
  }

  @Test
  void aFileThatIsNotRightIsRefusedWithItsNameAndTheRow() throws Exception {
    Path data = Files.createDirectories(dir.resolve("data"));
    Path file = data.resolve("person.csv");
    String head = "id,created,updated,created_by,updated_by,name,age\n";
    String ok = "a,2026-11-01T10:00:00Z,2026-11-01T10:00:00Z,user,user,Mei,";
    record Bad(String content, String message) {}
    for (Bad bad :
        List.of(
            new Bad(
                "id,created,updated,created_by,updated_by,colour\n",
                "the column 'colour' is not a field of type 'person'"),
            new Bad("id,created,updated,created_by,name\n", "the column 'updated_by' is missing"),
            new Bad(
                "id,id,created,updated,created_by,updated_by\n", "the column 'id' is there twice"),
            new Bad(head + ok + "old\n", "row 1: field 'age' must be a whole number"),
            new Bad(head + ok + "1\n" + ok + "2\n", "the id a is used twice"),
            new Bad(
                head + ",2026-11-01T10:00:00Z,2026-11-01T10:00:00Z,user,user,Mei,1\n",
                "row 1: \"id\" is empty"),
            new Bad(
                head + "a,yesterday,2026-11-01T10:00:00Z,user,user,Mei,1\n",
                "\"created\" must be a time"),
            new Bad(head + ok + "1,2\n", "more cells than the header has columns"),
            new Bad(head + "a,\"never closed\n", "a quote is not closed"),
            new Bad(head + "a,\"closed\"x,\n", "text after the closing quote"))) {
      Files.writeString(file, bad.content());
      CsvFileStorage s = new CsvFileStorage();
      s.configure(Map.of());
      s.useDataDirectory(data);
      assertThatThrownBy(() -> s.open(List.of(PERSON)))
          .as(bad.message())
          .isInstanceOf(StorageException.class)
          .hasMessageContaining(file.toString())
          .hasMessageContaining(bad.message());
    }
  }

  @Test
  void settingsAreChecked() {
    CsvFileStorage s = new CsvFileStorage();
    assertThatThrownBy(() -> s.configure(Map.of("colour", "red")))
        .isInstanceOf(CapabilityException.class)
        .hasMessageContaining("unknown setting 'colour'")
        .hasMessageContaining("delimiter");
    for (Object bad : List.of(",,", "", "\"", 5L)) {
      assertThatThrownBy(() -> s.configure(Map.of("delimiter", bad)))
          .hasMessageContaining("'delimiter' must be one character");
    }
    assertThatThrownBy(() -> s.configure(Map.of("escape_formulas", "no")))
        .hasMessageContaining("'escape_formulas' must be true or false");
  }

  @Test
  void theParserReadsQuotesNewlinesAndEmptyCells() throws Exception {
    var records =
        Csv.parse("a,\"b\"\"c\",\"d\ne\",,\"\"\nx\n".getBytes(StandardCharsets.UTF_8), ',');
    assertThat(records).hasSize(2);
    assertThat(records.get(0))
        .extracting(Csv.Cell::text)
        .containsExactly("a", "b\"c", "d\ne", "", "");
    assertThat(records.get(0))
        .extracting(Csv.Cell::missing)
        .containsExactly(false, false, false, true, false);
    assertThat(records.get(1)).extracting(Csv.Cell::text).containsExactly("x");
    assertThat(Csv.parse(new byte[0], ',')).isEmpty();
  }
}

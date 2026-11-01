// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.csvfile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.capability.StorageException;
import it.uhde.praksis.capability.StorageType;
import it.uhde.praksis.capability.StoredEntity;
import it.uhde.praksis.module.csvfile.Csv.Cell;
import it.uhde.praksis.storage.files.FileStorage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * An entity storage that keeps the entities of each type in one CSV file, {@code <type>.csv}, that
 * a spreadsheet opens. The first line names the columns: {@code id, created, updated, created_by,
 * updated_by} and then the fields of the type. A missing value is an empty cell, an empty text is
 * {@code ""}; times are ISO-8601 text, bytes are Base64, a list or a map is JSON in the cell.
 * Columns may be in any order when the file is read, and a field that has no column yet is simply
 * missing; the file is always written with all of them in the order above. A byte order mark and
 * any line end are accepted.
 *
 * <p>A text that starts with {@code =}, {@code +}, {@code -}, {@code @}, a tab or a carriage return
 * would be run as a formula by a spreadsheet, and the texts in entities can come from mail, so such
 * a text is written with a {@code '} in front and read back without it ({@code escape_formulas},
 * default true). Settings: {@code directory}, {@code delimiter} (one character, default {@code ,};
 * {@code ;} suits a German spreadsheet) and {@code escape_formulas}.
 */
public final class CsvFileStorage extends FileStorage {
  /** The name of the backend in {@code praksis.toml}. */
  public static final String NAME = "csv-file";

  private static final List<String> BUILT_IN =
      List.of("id", "created", "updated", "created_by", "updated_by");

  /** A text that a spreadsheet would run, with any quotes already in front of it. */
  private static final Pattern FORMULA = Pattern.compile("^'*[=+\\-@\\t\\r].*", Pattern.DOTALL);

  /** A text that was escaped when it was written. */
  private static final Pattern ESCAPED = Pattern.compile("^'+[=+\\-@\\t\\r].*", Pattern.DOTALL);

  private final ObjectMapper json = new ObjectMapper();
  private char delimiter = ',';
  private boolean escapeFormulas = true;

  @Override
  public String name() {
    return NAME;
  }

  @Override
  protected String extension() {
    return "csv";
  }

  @Override
  protected Set<String> ownSettings() {
    return Set.of("delimiter", "escape_formulas");
  }

  @Override
  protected void configureFormat(Map<String, Object> settings) throws CapabilityException {
    Object d = settings.get("delimiter");
    if (d != null) {
      if (!(d instanceof String s) || s.length() != 1 || "\"\n\r".contains(s)) {
        throw new CapabilityException(
            "'delimiter' must be one character that is not a quote or a line end", false);
      }
      delimiter = s.charAt(0);
    }
    Object e = settings.get("escape_formulas");
    if (e != null && !(e instanceof Boolean)) {
      throw new CapabilityException("'escape_formulas' must be true or false", false);
    }
    escapeFormulas = e == null || (Boolean) e;
  }

  // --- reading ---

  @Override
  protected List<StoredEntity> read(StorageType type, byte[] content) throws StorageException {
    List<List<Cell>> records = Csv.parse(content, delimiter);
    List<StoredEntity> out = new ArrayList<>();
    if (records.isEmpty()) {
      return out;
    }
    Map<String, Integer> column = new HashMap<>();
    List<Cell> header = records.get(0);
    for (int i = 0; i < header.size(); i++) {
      String name = header.get(i).text().strip();
      if (!BUILT_IN.contains(name) && type.field(name) == null) {
        throw new StorageException(
            "the column '" + name + "' is not a field of type '" + type.id() + "'");
      }
      if (column.put(name, i) != null) {
        throw new StorageException("the column '" + name + "' is there twice");
      }
    }
    for (String name : BUILT_IN) {
      if (!column.containsKey(name)) {
        throw new StorageException("the column '" + name + "' is missing");
      }
    }
    for (int r = 1; r < records.size(); r++) {
      List<Cell> row = records.get(r);
      try {
        out.add(entity(type, column, row));
      } catch (StorageException e) {
        throw new StorageException("row " + r + ": " + e.getMessage(), e);
      }
    }
    return out;
  }

  private StoredEntity entity(StorageType type, Map<String, Integer> column, List<Cell> row)
      throws StorageException {
    if (row.size() > column.size()) {
      throw new StorageException("it has more cells than the header has columns");
    }
    String id = required(row, column, "id");
    Instant created = time(required(row, column, "created"), "created");
    Instant updated = time(required(row, column, "updated"), "updated");
    String createdBy = required(row, column, "created_by");
    String updatedBy = required(row, column, "updated_by");
    Map<String, Object> fields = new LinkedHashMap<>();
    for (StorageType.Field f : type.fields()) {
      Integer at = column.get(f.name());
      Cell cell = at == null || at >= row.size() ? null : row.get(at);
      if (cell != null && !cell.missing()) {
        fields.put(f.name(), value(f, cell));
      }
    }
    return new StoredEntity(id, created, updated, createdBy, updatedBy, fields);
  }

  private static String required(List<Cell> row, Map<String, Integer> column, String name)
      throws StorageException {
    int at = column.get(name);
    if (at >= row.size() || row.get(at).missing()) {
      throw new StorageException("\"" + name + "\" is empty");
    }
    return row.get(at).text();
  }

  private static Instant time(String text, String name) throws StorageException {
    try {
      return Instant.parse(text.strip());
    } catch (DateTimeParseException e) {
      throw new StorageException("\"" + name + "\" must be a time like 2026-11-01T10:00:00Z");
    }
  }

  private Object value(StorageType.Field f, Cell cell) throws StorageException {
    String where = "field '" + f.name() + "'";
    String text = cell.text();
    try {
      return switch (f.type()) {
        case STRING -> escapeFormulas && ESCAPED.matcher(text).matches() ? text.substring(1) : text;
        case INT -> Long.parseLong(text.strip());
        case DOUBLE -> {
          double d = Double.parseDouble(text.strip());
          if (!Double.isFinite(d)) {
            throw new StorageException(where + " must be a number");
          }
          yield d;
        }
        case BOOL -> {
          String b = text.strip().toLowerCase(Locale.ROOT);
          if (!b.equals("true") && !b.equals("false")) {
            throw new StorageException(where + " must be true or false");
          }
          yield Boolean.parseBoolean(b);
        }
        case TIMESTAMP -> Instant.parse(text.strip());
        case BYTES -> Base64.getDecoder().decode(text.strip());
        case LIST -> {
          JsonNode n = json.readTree(text);
          if (n == null || !n.isArray()) {
            throw new StorageException(where + " must be a JSON list");
          }
          yield json.convertValue(n, List.class);
        }
        case MAP -> {
          JsonNode n = json.readTree(text);
          if (n == null || !n.isObject()) {
            throw new StorageException(where + " must be a JSON object");
          }
          yield json.convertValue(n, Map.class);
        }
      };
    } catch (NumberFormatException e) {
      throw new StorageException(
          where
              + " must be "
              + (f.type() == it.uhde.praksis.capability.ValueType.INT
                  ? "a whole number"
                  : "a number"));
    } catch (DateTimeParseException | IllegalArgumentException | JsonProcessingException e) {
      throw new StorageException(where + " is not a valid " + f.type().id(), e);
    }
  }

  // --- writing ---

  @Override
  protected byte[] write(StorageType type, Collection<StoredEntity> entities)
      throws StorageException {
    StringBuilder out = new StringBuilder();
    List<Cell> header = new ArrayList<>();
    BUILT_IN.forEach(n -> header.add(new Cell(n, false)));
    type.fields().forEach(f -> header.add(new Cell(f.name(), false)));
    Csv.append(out, header, delimiter);
    for (StoredEntity e : entities) {
      List<Cell> row = new ArrayList<>();
      row.add(new Cell(e.id(), false));
      row.add(new Cell(e.created().toString(), false));
      row.add(new Cell(e.updated().toString(), false));
      row.add(new Cell(e.createdBy(), false));
      row.add(new Cell(e.updatedBy(), false));
      for (StorageType.Field f : type.fields()) {
        Object v = e.fields().get(f.name());
        row.add(v == null ? new Cell("", false) : new Cell(text(f, v), true));
      }
      Csv.append(out, row, delimiter);
    }
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }

  /** The text of a value; {@link Csv#append} quotes it where it must be, and an empty text. */
  private String text(StorageType.Field f, Object v) throws StorageException {
    try {
      return switch (f.type()) {
        case STRING -> {
          String s = (String) v;
          yield escapeFormulas && FORMULA.matcher(s).matches() ? "'" + s : s;
        }
        case INT -> Long.toString((Long) v);
        case DOUBLE -> Double.toString((Double) v);
        case BOOL -> Boolean.toString((Boolean) v);
        case TIMESTAMP -> ((Instant) v).toString();
        case BYTES -> Base64.getEncoder().encodeToString((byte[]) v);
        case LIST, MAP -> json.writeValueAsString(v);
      };
    } catch (JsonProcessingException e) {
      throw new StorageException("cannot write JSON: " + e.getOriginalMessage(), e);
    }
  }
}

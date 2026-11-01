// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.jsonfile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.capability.EntityStorage;
import it.uhde.praksis.capability.Filter;
import it.uhde.praksis.capability.FilterMatcher;
import it.uhde.praksis.capability.Query;
import it.uhde.praksis.capability.StorageException;
import it.uhde.praksis.capability.StorageType;
import it.uhde.praksis.capability.StoredEntity;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * An entity storage that keeps the entities of each type in one readable JSON file, {@code
 * <type>.json}:
 *
 * <pre>
 * { "format": 1, "type": "contact", "entities": [
 *   { "id": "...", "created": "2026-11-01T10:00:00Z", "updated": "...",
 *     "created_by": "user", "updated_by": "user", "fields": { "name": "Mei", "age": 41 } } ] }
 * </pre>
 *
 * Times are ISO-8601 text and bytes are Base64. The files are read into memory and written whole
 * after every change, to a temporary file that replaces the old one, so a crash leaves the old or
 * the new file and never half of one. A file that is changed by hand (or by another program) while
 * Praksis runs is read again before the next operation, so a file can also be used to import and to
 * export; Praksis is the only writer that is safe to run at the same time. Settings: {@code
 * directory} (default: the module's own directory in the Praksis home) and {@code pretty}
 * (indented, default true).
 */
public final class JsonFileStorage implements EntityStorage {
  /** The name of the backend in {@code praksis.toml}. */
  public static final String NAME = "json-file";

  private static final int FORMAT = 1;
  private static final Set<String> SETTINGS = Set.of("directory", "pretty");

  private final ObjectMapper json = new ObjectMapper();
  private final Map<String, TypeFile> files = new HashMap<>();
  private String directorySetting;
  private boolean pretty = true;
  private Path dataDirectory;
  private Path directory;

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public synchronized void configure(Map<String, Object> settings) throws CapabilityException {
    for (String key : settings.keySet()) {
      if (!SETTINGS.contains(key)) {
        throw new CapabilityException(
            "unknown setting '" + key + "'; the settings are " + new java.util.TreeSet<>(SETTINGS),
            false);
      }
    }
    Object dir = settings.get("directory");
    if (dir != null && !(dir instanceof String s && !s.isBlank())) {
      throw new CapabilityException("'directory' must be a path", false);
    }
    directorySetting = (String) dir;
    Object p = settings.get("pretty");
    if (p != null && !(p instanceof Boolean)) {
      throw new CapabilityException("'pretty' must be true or false", false);
    }
    pretty = p == null || (Boolean) p;
  }

  @Override
  public synchronized void useDataDirectory(Path dir) {
    this.dataDirectory = dir;
  }

  /** One type: its file, its entities in memory, and how the file looked when they were read. */
  private static final class TypeFile {
    final StorageType type;
    final Path file;
    final Map<String, StoredEntity> entities = new LinkedHashMap<>();
    long modified = -1;
    long size = -1;

    TypeFile(StorageType type, Path file) {
      this.type = type;
      this.file = file;
    }
  }

  @Override
  public synchronized void open(List<StorageType> types) throws StorageException {
    if (directorySetting != null) {
      Path d = Path.of(directorySetting);
      directory = d.isAbsolute() || dataDirectory == null ? d : dataDirectory.resolve(d);
    } else if (dataDirectory != null) {
      directory = dataDirectory;
    } else {
      throw new StorageException("no directory: set 'directory' for this storage");
    }
    directory = directory.toAbsolutePath().normalize();
    try {
      Files.createDirectories(directory);
    } catch (IOException e) {
      throw new StorageException(
          "cannot make the directory " + directory + ": " + e.getMessage(), e);
    }
    for (StorageType type : types) {
      TypeFile f = new TypeFile(type, directory.resolve(type.id() + ".json"));
      load(f);
      files.put(type.id(), f);
    }
  }

  private TypeFile file(String type) throws StorageException {
    TypeFile f = files.get(type);
    if (f == null) {
      throw new StorageException("the storage 'json-file' was not opened for type '" + type + "'");
    }
    refresh(f);
    return f;
  }

  // --- reading ---

  /** Reads the file again if it is not as it was when it was read. */
  private void refresh(TypeFile f) throws StorageException {
    try {
      long modified = Files.exists(f.file) ? Files.getLastModifiedTime(f.file).toMillis() : -1;
      long size = Files.exists(f.file) ? Files.size(f.file) : -1;
      if (modified != f.modified || size != f.size) {
        load(f);
      }
    } catch (IOException e) {
      throw new StorageException("cannot look at " + f.file + ": " + e.getMessage(), e);
    }
  }

  private void load(TypeFile f) throws StorageException {
    f.entities.clear();
    if (!Files.exists(f.file)) {
      f.modified = -1;
      f.size = -1;
      return;
    }
    JsonNode root;
    try {
      f.modified = Files.getLastModifiedTime(f.file).toMillis();
      f.size = Files.size(f.file);
      root = json.readTree(Files.readAllBytes(f.file));
    } catch (JsonProcessingException e) {
      throw new StorageException(f.file + " is not valid JSON: " + e.getOriginalMessage(), e);
    } catch (IOException e) {
      throw new StorageException("cannot read " + f.file + ": " + e.getMessage(), e);
    }
    if (root == null || !root.isObject()) {
      throw new StorageException(f.file + ": the file must hold one JSON object");
    }
    if (root.path("format").asInt(-1) != FORMAT) {
      throw new StorageException(f.file + ": \"format\" must be " + FORMAT);
    }
    if (!f.type.id().equals(root.path("type").asText(null))) {
      throw new StorageException(
          f.file + ": \"type\" must be \"" + f.type.id() + "\", not " + root.path("type"));
    }
    JsonNode list = root.path("entities");
    if (!list.isArray()) {
      throw new StorageException(f.file + ": \"entities\" must be a list");
    }
    int n = 0;
    for (JsonNode e : list) {
      n++;
      try {
        StoredEntity entity = entity(f.type, e);
        if (f.entities.put(entity.id(), entity) != null) {
          throw new StorageException("the id " + entity.id() + " is used twice");
        }
      } catch (StorageException ex) {
        throw new StorageException(f.file + ": entity " + n + ": " + ex.getMessage(), ex);
      }
    }
  }

  private StoredEntity entity(StorageType type, JsonNode e) throws StorageException {
    if (!e.isObject()) {
      throw new StorageException("must be an object");
    }
    String id = text(e, "id");
    Instant created = time(e, "created");
    Instant updated = time(e, "updated");
    String createdBy = text(e, "created_by");
    String updatedBy = text(e, "updated_by");
    JsonNode fields = e.path("fields");
    if (!fields.isObject()) {
      throw new StorageException("\"fields\" must be an object");
    }
    Map<String, Object> values = new LinkedHashMap<>();
    for (StorageType.Field f : type.fields()) {
      JsonNode v = fields.get(f.name());
      if (v != null && !v.isNull()) {
        values.put(f.name(), fromJson(f, v));
      }
    }
    for (String name : (Iterable<String>) fields::fieldNames) {
      if (type.field(name) == null) {
        throw new StorageException(
            "the field '" + name + "' is not declared by type '" + type.id() + "'");
      }
    }
    return new StoredEntity(id, created, updated, createdBy, updatedBy, values);
  }

  private static String text(JsonNode e, String key) throws StorageException {
    JsonNode v = e.get(key);
    if (v == null || !v.isTextual() || v.asText().isEmpty()) {
      throw new StorageException("\"" + key + "\" must be a text");
    }
    return v.asText();
  }

  private static Instant time(JsonNode e, String key) throws StorageException {
    try {
      return Instant.parse(text(e, key));
    } catch (DateTimeParseException ex) {
      throw new StorageException("\"" + key + "\" must be a time like 2026-11-01T10:00:00Z");
    }
  }

  private Object fromJson(StorageType.Field f, JsonNode v) throws StorageException {
    String where = "field '" + f.name() + "'";
    try {
      return switch (f.type()) {
        case STRING -> {
          if (!v.isTextual()) {
            throw new StorageException(where + " must be a text");
          }
          yield v.asText();
        }
        case INT -> {
          if (!v.isIntegralNumber()) {
            throw new StorageException(where + " must be a whole number");
          }
          yield v.asLong();
        }
        case DOUBLE -> {
          if (!v.isNumber()) {
            throw new StorageException(where + " must be a number");
          }
          yield v.asDouble();
        }
        case BOOL -> {
          if (!v.isBoolean()) {
            throw new StorageException(where + " must be true or false");
          }
          yield v.asBoolean();
        }
        case TIMESTAMP -> {
          if (!v.isTextual()) {
            throw new StorageException(where + " must be a time");
          }
          yield Instant.parse(v.asText());
        }
        case BYTES -> {
          if (!v.isTextual()) {
            throw new StorageException(where + " must be Base64");
          }
          yield Base64.getDecoder().decode(v.asText());
        }
        case LIST -> {
          if (!v.isArray()) {
            throw new StorageException(where + " must be a list");
          }
          yield json.convertValue(v, List.class);
        }
        case MAP -> {
          if (!v.isObject()) {
            throw new StorageException(where + " must be an object");
          }
          yield json.convertValue(v, Map.class);
        }
      };
    } catch (DateTimeParseException | IllegalArgumentException e) {
      throw new StorageException(where + " is not a valid " + f.type().id(), e);
    }
  }

  // --- writing ---

  private Object toJson(StorageType.Field f, Object v) {
    return switch (f.type()) {
      case TIMESTAMP -> ((Instant) v).toString();
      case BYTES -> Base64.getEncoder().encodeToString((byte[]) v);
      default -> v;
    };
  }

  /** Writes the whole file to a temporary file in the same directory and puts it in place. */
  private void save(TypeFile f) throws StorageException {
    ObjectNode root = json.createObjectNode();
    root.put("format", FORMAT);
    root.put("type", f.type.id());
    ArrayNode list = root.putArray("entities");
    for (StoredEntity e : f.entities.values()) {
      ObjectNode o = list.addObject();
      o.put("id", e.id());
      o.put("created", e.created().toString());
      o.put("updated", e.updated().toString());
      o.put("created_by", e.createdBy());
      o.put("updated_by", e.updatedBy());
      ObjectNode fields = o.putObject("fields");
      for (StorageType.Field field : f.type.fields()) {
        Object v = e.fields().get(field.name());
        if (v != null) {
          fields.set(field.name(), json.valueToTree(toJson(field, v)));
        }
      }
    }
    Path temp = f.file.resolveSibling(f.file.getFileName() + ".tmp");
    try {
      byte[] bytes =
          (pretty ? json.writerWithDefaultPrettyPrinter() : json.writer()).writeValueAsBytes(root);
      Files.deleteIfExists(temp);
      try {
        Files.createFile(
            temp,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      } catch (UnsupportedOperationException e) {
        Files.createFile(temp);
      }
      try (FileChannel c = FileChannel.open(temp, StandardOpenOption.WRITE)) {
        c.write(java.nio.ByteBuffer.wrap(bytes));
        c.force(true);
      }
      try {
        Files.move(
            temp, f.file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, f.file, StandardCopyOption.REPLACE_EXISTING);
      }
      f.modified = Files.getLastModifiedTime(f.file).toMillis();
      f.size = Files.size(f.file);
    } catch (IOException e) {
      try {
        Files.deleteIfExists(temp);
      } catch (IOException ignored) {
        // the write failed already; the temporary file is harmless
      }
      throw new StorageException("cannot write " + f.file + ": " + e.getMessage(), e);
    }
  }

  // --- the storage ---

  @Override
  public synchronized Optional<StoredEntity> get(String type, String id) throws StorageException {
    return Optional.ofNullable(file(type).entities.get(id));
  }

  @Override
  public synchronized void insert(String type, StoredEntity entity) throws StorageException {
    TypeFile f = file(type);
    if (f.entities.containsKey(entity.id())) {
      throw new StorageException("the id " + entity.id() + " is in use");
    }
    f.entities.put(entity.id(), entity);
    try {
      save(f);
    } catch (StorageException e) {
      f.entities.remove(entity.id());
      throw e;
    }
  }

  @Override
  public synchronized void replace(String type, StoredEntity entity) throws StorageException {
    TypeFile f = file(type);
    StoredEntity before = f.entities.get(entity.id());
    if (before == null) {
      throw new StorageException("there is no " + type + " " + entity.id());
    }
    f.entities.put(entity.id(), entity);
    try {
      save(f);
    } catch (StorageException e) {
      f.entities.put(entity.id(), before);
      throw e;
    }
  }

  @Override
  public synchronized boolean delete(String type, String id) throws StorageException {
    TypeFile f = file(type);
    StoredEntity before = f.entities.remove(id);
    if (before == null) {
      return false;
    }
    try {
      save(f);
    } catch (StorageException e) {
      f.entities.put(id, before);
      throw e;
    }
    return true;
  }

  @Override
  public synchronized Page query(String type, Query query) throws StorageException {
    return FilterMatcher.page(new ArrayList<>(file(type).entities.values()), query);
  }

  @Override
  public synchronized Optional<String> findUnique(String type, String field, Object value)
      throws StorageException {
    Filter equals = new Filter.Equals(field, value);
    return file(type).entities.values().stream()
        .filter(e -> FilterMatcher.matches(equals, e))
        .map(StoredEntity::id)
        .findFirst();
  }
}

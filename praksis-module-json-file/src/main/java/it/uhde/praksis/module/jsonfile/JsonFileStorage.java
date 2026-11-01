// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.jsonfile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.capability.StorageException;
import it.uhde.praksis.capability.StorageType;
import it.uhde.praksis.capability.StoredEntity;
import it.uhde.praksis.storage.files.FileStorage;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * Times are ISO-8601 text and bytes are Base64. How the files are kept (read again after a change
 * by hand, written so that a crash cannot half-write them) is that of {@link FileStorage}.
 * Settings: {@code directory} and {@code pretty} (indented, default true).
 */
public final class JsonFileStorage extends FileStorage {
  /** The name of the backend in {@code praksis.toml}. */
  public static final String NAME = "json-file";

  private static final int FORMAT = 1;

  private final ObjectMapper json = new ObjectMapper();
  private boolean pretty = true;

  @Override
  public String name() {
    return NAME;
  }

  @Override
  protected String extension() {
    return "json";
  }

  @Override
  protected Set<String> ownSettings() {
    return Set.of("pretty");
  }

  @Override
  protected void configureFormat(Map<String, Object> settings) throws CapabilityException {
    Object p = settings.get("pretty");
    if (p != null && !(p instanceof Boolean)) {
      throw new CapabilityException("'pretty' must be true or false", false);
    }
    pretty = p == null || (Boolean) p;
  }

  @Override
  protected List<StoredEntity> read(StorageType type, byte[] content) throws StorageException {
    JsonNode root;
    try {
      root = json.readTree(content);
    } catch (JsonProcessingException e) {
      throw new StorageException("is not valid JSON: " + e.getOriginalMessage(), e);
    } catch (IOException e) {
      throw new StorageException("cannot be read: " + e.getMessage(), e);
    }
    if (root == null || !root.isObject()) {
      throw new StorageException("the file must hold one JSON object");
    }
    if (root.path("format").asInt(-1) != FORMAT) {
      throw new StorageException("\"format\" must be " + FORMAT);
    }
    if (!type.id().equals(root.path("type").asText(null))) {
      throw new StorageException(
          "\"type\" must be \"" + type.id() + "\", not " + root.path("type"));
    }
    JsonNode list = root.path("entities");
    if (!list.isArray()) {
      throw new StorageException("\"entities\" must be a list");
    }
    List<StoredEntity> out = new java.util.ArrayList<>();
    int n = 0;
    for (JsonNode e : list) {
      n++;
      try {
        out.add(entity(type, e));
      } catch (StorageException ex) {
        throw new StorageException("entity " + n + ": " + ex.getMessage(), ex);
      }
    }
    return out;
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

  private Object toJson(StorageType.Field f, Object v) {
    return switch (f.type()) {
      case TIMESTAMP -> ((Instant) v).toString();
      case BYTES -> Base64.getEncoder().encodeToString((byte[]) v);
      default -> v;
    };
  }

  @Override
  protected byte[] write(StorageType type, Collection<StoredEntity> entities)
      throws StorageException {
    ObjectNode root = json.createObjectNode();
    root.put("format", FORMAT);
    root.put("type", type.id());
    ArrayNode list = root.putArray("entities");
    for (StoredEntity e : entities) {
      ObjectNode o = list.addObject();
      o.put("id", e.id());
      o.put("created", e.created().toString());
      o.put("updated", e.updated().toString());
      o.put("created_by", e.createdBy());
      o.put("updated_by", e.updatedBy());
      ObjectNode fields = o.putObject("fields");
      for (StorageType.Field field : type.fields()) {
        Object v = e.fields().get(field.name());
        if (v != null) {
          fields.set(field.name(), json.valueToTree(toJson(field, v)));
        }
      }
    }
    try {
      return (pretty ? json.writerWithDefaultPrettyPrinter() : json.writer())
          .writeValueAsBytes(root);
    } catch (JsonProcessingException e) {
      throw new StorageException("cannot write JSON: " + e.getOriginalMessage(), e);
    }
  }
}

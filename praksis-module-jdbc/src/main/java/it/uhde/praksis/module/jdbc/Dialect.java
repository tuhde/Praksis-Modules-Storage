// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.jdbc;

import it.uhde.praksis.capability.ValueType;
import java.util.Locale;
import java.util.Optional;

/**
 * What differs between databases in what the storage does: the type of a column. Everything else
 * (quoting, limits, null order, the filter) is written so that it needs no difference.
 *
 * @param name the name in the setting {@code dialect}
 * @param id the type of the column {@code id}
 * @param text the type of a text of any length (also the JSON of a list or a map)
 * @param bytes the type of bytes of any length
 */
record Dialect(String name, String id, String text, String bytes) {
  static final Dialect SQLITE = new Dialect("sqlite", "TEXT", "TEXT", "BLOB");
  static final Dialect H2 = new Dialect("h2", "VARCHAR(100)", "VARCHAR", "VARBINARY");
  static final Dialect POSTGRESQL = new Dialect("postgresql", "VARCHAR(100)", "TEXT", "BYTEA");

  static final java.util.List<Dialect> ALL = java.util.List.of(SQLITE, H2, POSTGRESQL);

  /** The dialect for a database product name as the driver reports it, or empty. */
  static Optional<Dialect> of(String productName) {
    String p = productName.toLowerCase(Locale.ROOT);
    return ALL.stream().filter(d -> p.contains(d.name())).findFirst();
  }

  static Optional<Dialect> named(String name) {
    return ALL.stream().filter(d -> d.name().equals(name)).findFirst();
  }

  /** The type of the column for a field. Times are stored as milliseconds since 1970. */
  String column(ValueType type) {
    return switch (type) {
      case STRING, LIST, MAP -> text;
      case INT, TIMESTAMP -> "BIGINT";
      case DOUBLE -> "DOUBLE PRECISION";
      case BOOL -> "BOOLEAN";
      case BYTES -> bytes;
    };
  }
}

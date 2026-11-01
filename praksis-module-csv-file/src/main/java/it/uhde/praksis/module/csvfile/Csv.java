// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.csvfile;

import it.uhde.praksis.capability.StorageException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes CSV as RFC 4180 describes it, with two additions: any of the line ends {@code
 * \n}, {@code \r\n} and {@code \r} is read, and a cell that was written between quotes is told
 * apart from one that was not, so that an empty text ({@code ""}) is not the same as a missing
 * value (nothing between the delimiters).
 */
final class Csv {
  private Csv() {}

  /**
   * One cell.
   *
   * @param text the text, without the quotes around it
   * @param quoted whether it was written between quotes
   */
  record Cell(String text, boolean quoted) {
    /** Whether there is nothing in the cell, not even an empty text. */
    boolean missing() {
      return !quoted && text.isEmpty();
    }
  }

  /** The records of the content, without blank lines. A leading byte order mark is ignored. */
  static List<List<Cell>> parse(byte[] content, char delimiter) throws StorageException {
    String s = new String(content, StandardCharsets.UTF_8);
    if (s.startsWith("﻿")) {
      s = s.substring(1);
    }
    List<List<Cell>> records = new ArrayList<>();
    List<Cell> row = new ArrayList<>();
    StringBuilder cell = new StringBuilder();
    boolean quoted = false;
    boolean inQuotes = false;
    boolean closed = false; // a quoted cell ended; only a delimiter or a line end may follow
    int line = 1;
    int i = 0;
    while (i < s.length()) {
      char c = s.charAt(i);
      if (inQuotes) {
        if (c == '"') {
          if (i + 1 < s.length() && s.charAt(i + 1) == '"') {
            cell.append('"');
            i += 2;
            continue;
          }
          inQuotes = false;
          closed = true;
        } else {
          if (c == '\n') {
            line++;
          }
          cell.append(c);
        }
        i++;
        continue;
      }
      if (c == delimiter) {
        row.add(new Cell(cell.toString(), quoted));
        cell.setLength(0);
        quoted = false;
        closed = false;
        i++;
      } else if (c == '\n' || c == '\r') {
        if (c == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') {
          i++;
        }
        i++;
        row.add(new Cell(cell.toString(), quoted));
        endRecord(records, row);
        row = new ArrayList<>();
        cell.setLength(0);
        quoted = false;
        closed = false;
        line++;
      } else if (c == '"' && cell.isEmpty() && !quoted) {
        inQuotes = true;
        quoted = true;
        i++;
      } else {
        if (closed) {
          throw new StorageException("line " + line + ": text after the closing quote of a cell");
        }
        cell.append(c);
        i++;
      }
    }
    if (inQuotes) {
      throw new StorageException("line " + line + ": a quote is not closed");
    }
    if (!row.isEmpty() || !cell.isEmpty() || quoted) {
      row.add(new Cell(cell.toString(), quoted));
      endRecord(records, row);
    }
    return records;
  }

  private static void endRecord(List<List<Cell>> records, List<Cell> row) {
    boolean blank = row.size() == 1 && row.get(0).missing();
    if (!blank) {
      records.add(row);
    }
  }

  /** One record as a line, with the line end. Texts are quoted where they must be. */
  static void append(StringBuilder out, List<Cell> row, char delimiter) {
    boolean first = true;
    for (Cell cell : row) {
      if (!first) {
        out.append(delimiter);
      }
      first = false;
      if (cell == null || cell.missing()) {
        continue;
      }
      String t = cell.text();
      boolean quote =
          t.isEmpty()
              || t.indexOf(delimiter) >= 0
              || t.indexOf('"') >= 0
              || t.indexOf('\n') >= 0
              || t.indexOf('\r') >= 0
              || t.startsWith(" ")
              || t.endsWith(" ");
      if (quote) {
        out.append('"').append(t.replace("\"", "\"\"")).append('"');
      } else {
        out.append(t);
      }
    }
    out.append('\n');
  }
}

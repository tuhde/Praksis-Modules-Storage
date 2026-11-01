// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.jdbc;

import it.uhde.praksis.capability.StorageException;
import it.uhde.praksis.capability.ValueType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lists and maps as JSON text in a column. Small and exact for what entities hold (text, numbers,
 * true, false, nothing, lists, maps), so the module needs no library for it.
 */
final class Json {
  private Json() {}

  static String write(Object v) {
    StringBuilder sb = new StringBuilder();
    write(sb, v);
    return sb.toString();
  }

  private static void write(StringBuilder sb, Object v) {
    switch (v) {
      case null -> sb.append("null");
      case String s -> {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
          char c = s.charAt(i);
          switch (c) {
            case '"' -> sb.append("\\\"");
            case '\\' -> sb.append("\\\\");
            case '\n' -> sb.append("\\n");
            case '\r' -> sb.append("\\r");
            case '\t' -> sb.append("\\t");
            default -> {
              if (c < 0x20) {
                sb.append(String.format("\\u%04x", (int) c));
              } else {
                sb.append(c);
              }
            }
          }
        }
        sb.append('"');
      }
      case Boolean b -> sb.append(b);
      case Number n -> sb.append(n);
      case List<?> l -> {
        sb.append('[');
        boolean first = true;
        for (Object o : l) {
          if (!first) {
            sb.append(',');
          }
          first = false;
          write(sb, o);
        }
        sb.append(']');
      }
      case Map<?, ?> m -> {
        sb.append('{');
        boolean first = true;
        for (var e : m.entrySet()) {
          if (!first) {
            sb.append(',');
          }
          first = false;
          write(sb, String.valueOf(e.getKey()));
          sb.append(':');
          write(sb, e.getValue());
        }
        sb.append('}');
      }
      default -> write(sb, String.valueOf(v));
    }
  }

  /** The list or map in the text. */
  static Object read(String text, ValueType type) throws StorageException {
    Reader r = new Reader(text);
    Object v = r.value();
    r.end();
    if (type == ValueType.LIST && !(v instanceof List)) {
      throw new StorageException("a list was expected in " + text);
    }
    if (type == ValueType.MAP && !(v instanceof Map)) {
      throw new StorageException("a map was expected in " + text);
    }
    return v;
  }

  private static final class Reader {
    private final String s;
    private int i;

    Reader(String s) {
      this.s = s;
    }

    private StorageException bad(String what) {
      return new StorageException("not valid JSON at " + i + " (" + what + "): " + s);
    }

    private void skip() {
      while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
        i++;
      }
    }

    void end() throws StorageException {
      skip();
      if (i != s.length()) {
        throw bad("more text");
      }
    }

    Object value() throws StorageException {
      skip();
      if (i >= s.length()) {
        throw bad("nothing");
      }
      char c = s.charAt(i);
      if (c == '{') {
        i++;
        Map<String, Object> m = new LinkedHashMap<>();
        skip();
        if (peek('}')) {
          return m;
        }
        do {
          skip();
          String k = string();
          skip();
          expect(':');
          m.put(k, value());
          skip();
        } while (comma());
        expect('}');
        return m;
      }
      if (c == '[') {
        i++;
        List<Object> l = new ArrayList<>();
        skip();
        if (peek(']')) {
          return l;
        }
        do {
          l.add(value());
          skip();
        } while (comma());
        expect(']');
        return l;
      }
      if (c == '"') {
        return string();
      }
      if (s.startsWith("true", i)) {
        i += 4;
        return true;
      }
      if (s.startsWith("false", i)) {
        i += 5;
        return false;
      }
      if (s.startsWith("null", i)) {
        i += 4;
        return null;
      }
      int start = i;
      while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
        i++;
      }
      String n = s.substring(start, i);
      try {
        return n.matches("-?\\d+") ? (Object) Long.parseLong(n) : (Object) Double.parseDouble(n);
      } catch (NumberFormatException e) {
        throw bad("a number");
      }
    }

    private boolean peek(char c) {
      if (i < s.length() && s.charAt(i) == c) {
        i++;
        return true;
      }
      return false;
    }

    private boolean comma() {
      return peek(',');
    }

    private void expect(char c) throws StorageException {
      skip();
      if (!peek(c)) {
        throw bad("'" + c + "' expected");
      }
    }

    private String string() throws StorageException {
      skip();
      if (!peek('"')) {
        throw bad("a text");
      }
      StringBuilder sb = new StringBuilder();
      while (i < s.length()) {
        char c = s.charAt(i++);
        if (c == '"') {
          return sb.toString();
        }
        if (c == '\\') {
          if (i >= s.length()) {
            break;
          }
          char e = s.charAt(i++);
          switch (e) {
            case 'n' -> sb.append('\n');
            case 'r' -> sb.append('\r');
            case 't' -> sb.append('\t');
            case 'b' -> sb.append('\b');
            case 'f' -> sb.append('\f');
            case 'u' -> {
              if (i + 4 > s.length()) {
                throw bad("a \\u escape");
              }
              sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
              i += 4;
            }
            default -> sb.append(e);
          }
        } else {
          sb.append(c);
        }
      }
      throw bad("a text is not closed");
    }
  }
}

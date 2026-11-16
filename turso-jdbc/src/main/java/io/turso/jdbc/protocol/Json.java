package io.turso.jdbc.protocol;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal, dependency-free JSON reader/writer (RFC 8259 subset used by the protocol). */
public final class Json {
  private Json() {}

  public interface Body {
    void write(Writer w);
  }

  public static String write(Body body) {
    StringBuilder sb = new StringBuilder(256);
    Writer w = new Writer(sb);
    body.write(w);
    return sb.toString();
  }

  public static Object parse(String text) {
    return new Parser(text).parseDocument();
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> object(Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  public static List<Object> array(Object value) {
    return (List<Object>) value;
  }

  public static String string(Object value) {
    return value == null ? null : value.toString();
  }

  public static Number number(Object value) {
    return value instanceof Number n ? n : null;
  }

  public static final class Writer {
    private final StringBuilder sb;
    private final Deque<Boolean> first = new ArrayDeque<>();
    private boolean afterName;

    private Writer(StringBuilder sb) {
      this.sb = sb;
    }

    private void prepare() {
      if (afterName) {
        afterName = false;
        return;
      }
      if (!first.isEmpty()) {
        if (!first.peek()) {
          sb.append(',');
        }
        first.pop();
        first.push(Boolean.FALSE);
      }
    }

    public Writer beginObject() {
      prepare();
      sb.append('{');
      first.push(Boolean.TRUE);
      return this;
    }

    public Writer endObject() {
      sb.append('}');
      first.pop();
      return this;
    }

    public Writer beginArray() {
      prepare();
      sb.append('[');
      first.push(Boolean.TRUE);
      return this;
    }

    public Writer endArray() {
      sb.append(']');
      first.pop();
      return this;
    }

    public Writer name(String name) {
      prepare();
      quote(name);
      sb.append(':');
      afterName = true;
      return this;
    }

    public Writer value(Object value) {
      prepare();
      if (value == null) {
        sb.append("null");
      } else if (value instanceof String s) {
        quote(s);
      } else if (value instanceof Boolean b) {
        sb.append(b);
      } else if (value instanceof Number n) {
        if (n instanceof Double d && (d.isNaN() || d.isInfinite())) {
          throw new IllegalArgumentException("Non-finite number cannot be encoded as JSON");
        }
        if (n instanceof Float f && (f.isNaN() || f.isInfinite())) {
          throw new IllegalArgumentException("Non-finite number cannot be encoded as JSON");
        }
        sb.append(n);
      } else if (value instanceof Map<?, ?> map) {
        beginObject();
        for (Map.Entry<?, ?> e : map.entrySet()) {
          name(String.valueOf(e.getKey())).value(e.getValue());
        }
        endObject();
      } else if (value instanceof List<?> list) {
        beginArray();
        for (Object o : list) {
          value(o);
        }
        endArray();
      } else {
        throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass());
      }
      return this;
    }

    private void quote(String s) {
      sb.append('"');
      for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        switch (c) {
          case '"' -> sb.append("\\\"");
          case '\\' -> sb.append("\\\\");
          case '\b' -> sb.append("\\b");
          case '\f' -> sb.append("\\f");
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
  }

  private static final class Parser {
    private final String s;
    private int i;

    private Parser(String s) {
      this.s = s;
    }

    private Object parseDocument() {
      skipWs();
      Object value = parseValue();
      skipWs();
      if (i != s.length()) {
        throw error("Unexpected trailing content");
      }
      return value;
    }

    private Object parseValue() {
      if (i >= s.length()) {
        throw error("Unexpected end of input");
      }
      char c = s.charAt(i);
      return switch (c) {
        case '{' -> parseObject();
        case '[' -> parseArray();
        case '"' -> parseString();
        case 't' -> literal("true", Boolean.TRUE);
        case 'f' -> literal("false", Boolean.FALSE);
        case 'n' -> literal("null", null);
        default -> parseNumber();
      };
    }

    private Map<String, Object> parseObject() {
      i++; // {
      Map<String, Object> map = new LinkedHashMap<>();
      skipWs();
      if (peek() == '}') {
        i++;
        return map;
      }
      while (true) {
        skipWs();
        if (peek() != '"') {
          throw error("Expected object key");
        }
        String key = parseString();
        skipWs();
        expect(':');
        skipWs();
        map.put(key, parseValue());
        skipWs();
        char c = next();
        if (c == '}') {
          return map;
        }
        if (c != ',') {
          throw error("Expected ',' or '}'");
        }
      }
    }

    private List<Object> parseArray() {
      i++; // [
      List<Object> list = new ArrayList<>();
      skipWs();
      if (peek() == ']') {
        i++;
        return list;
      }
      while (true) {
        skipWs();
        list.add(parseValue());
        skipWs();
        char c = next();
        if (c == ']') {
          return list;
        }
        if (c != ',') {
          throw error("Expected ',' or ']'");
        }
      }
    }

    private String parseString() {
      expect('"');
      StringBuilder sb = new StringBuilder();
      while (true) {
        char c = next();
        if (c == '"') {
          return sb.toString();
        }
        if (c != '\\') {
          sb.append(c);
          continue;
        }
        char e = next();
        switch (e) {
          case '"' -> sb.append('"');
          case '\\' -> sb.append('\\');
          case '/' -> sb.append('/');
          case 'b' -> sb.append('\b');
          case 'f' -> sb.append('\f');
          case 'n' -> sb.append('\n');
          case 'r' -> sb.append('\r');
          case 't' -> sb.append('\t');
          case 'u' -> {
            if (i + 4 > s.length()) {
              throw error("Invalid unicode escape");
            }
            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
            i += 4;
          }
          default -> throw error("Invalid escape '\\" + e + "'");
        }
      }
    }

    private Object parseNumber() {
      int start = i;
      if (peek() == '-') {
        i++;
      }
      boolean fraction = false;
      while (i < s.length()) {
        char c = s.charAt(i);
        if (c >= '0' && c <= '9') {
          i++;
        } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
          fraction = true;
          i++;
        } else {
          break;
        }
      }
      String raw = s.substring(start, i);
      if (raw.isEmpty() || raw.equals("-")) {
        throw error("Invalid number");
      }
      try {
        if (fraction) {
          return Double.parseDouble(raw);
        }
        return Long.parseLong(raw);
      } catch (NumberFormatException ex) {
        try {
          return Double.parseDouble(raw);
        } catch (NumberFormatException ex2) {
          throw error("Invalid number '" + raw + "'");
        }
      }
    }

    private Object literal(String word, Object value) {
      if (!s.startsWith(word, i)) {
        throw error("Expected '" + word + "'");
      }
      i += word.length();
      return value;
    }

    private void skipWs() {
      while (i < s.length()) {
        char c = s.charAt(i);
        if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
          i++;
        } else {
          return;
        }
      }
    }

    private char peek() {
      if (i >= s.length()) {
        throw error("Unexpected end of input");
      }
      return s.charAt(i);
    }

    private char next() {
      if (i >= s.length()) {
        throw error("Unexpected end of input");
      }
      return s.charAt(i++);
    }

    private void expect(char c) {
      if (next() != c) {
        throw error("Expected '" + c + "'");
      }
    }

    private IllegalArgumentException error(String message) {
      return new IllegalArgumentException(message + " at offset " + i);
    }
  }
}

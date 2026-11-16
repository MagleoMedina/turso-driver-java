package io.turso.jdbc.protocol;

import java.math.BigDecimal;
import java.util.Base64;
import java.util.Map;

/** Encoding/decoding of SQL values as defined by the SQL over HTTP v3 protocol. */
public final class Values {
  private static final byte[] EMPTY = new byte[0];

  private Values() {}

  /** Decodes a protocol value object into Long, Double, String, byte[] or null. */
  public static Object decode(Object raw) {
    if (raw == null) {
      return null;
    }
    Map<String, Object> value = Json.object(raw);
    String type = Json.string(value.get("type"));
    return switch (type) {
      case "null" -> null;
      case "integer" -> Long.parseLong(Json.string(value.get("value")));
      case "float" -> {
        Object v = value.get("value");
        yield v == null ? Double.NaN : ((Number) v).doubleValue();
      }
      case "text" -> Json.string(value.get("value"));
      case "blob" -> decodeBlob(Json.string(value.get("base64")));
      default -> throw new IllegalArgumentException("Unknown protocol value type: " + type);
    };
  }

  /** Writes a protocol value object for a Java value already normalized by the JDBC layer. */
  public static void encode(Json.Writer w, Object value) {
    w.beginObject();
    if (value == null) {
      w.name("type").value("null");
    } else if (value instanceof Long || value instanceof Integer || value instanceof Short
        || value instanceof Byte) {
      w.name("type").value("integer");
      w.name("value").value(Long.toString(((Number) value).longValue()));
    } else if (value instanceof Double || value instanceof Float) {
      double d = ((Number) value).doubleValue();
      if (Double.isNaN(d) || Double.isInfinite(d)) {
        throw new IllegalArgumentException("Non-finite numbers cannot be sent to Turso");
      }
      w.name("type").value("float");
      w.name("value").value(d);
    } else if (value instanceof Boolean b) {
      w.name("type").value("integer");
      w.name("value").value(b ? "1" : "0");
    } else if (value instanceof byte[] bytes) {
      w.name("type").value("blob");
      w.name("base64").value(Base64.getEncoder().withoutPadding().encodeToString(bytes));
    } else if (value instanceof BigDecimal decimal) {
      // SQLite has no exact decimal type; text keeps the literal exact for TEXT columns.
      w.name("type").value("text");
      w.name("value").value(decimal.toPlainString());
    } else if (value instanceof CharSequence text) {
      w.name("type").value("text");
      w.name("value").value(text.toString());
    } else {
      throw new IllegalArgumentException("Unsupported SQL value: " + value.getClass().getName());
    }
    w.endObject();
  }

  public static void encodeNamed(Json.Writer w, String name, Object value) {
    w.beginObject();
    w.name("name").value(name);
    w.name("value");
    encode(w, value);
    w.endObject();
  }

  public static byte[] decodeBlob(String base64) {
    if (base64 == null || base64.isEmpty()) {
      return EMPTY;
    }
    int pad = (4 - base64.length() % 4) % 4;
    String padded = pad == 0 ? base64 : base64 + "=".repeat(pad);
    return Base64.getDecoder().decode(padded);
  }

  public static String encodeBlob(byte[] bytes) {
    return Base64.getEncoder().withoutPadding().encodeToString(bytes);
  }
}

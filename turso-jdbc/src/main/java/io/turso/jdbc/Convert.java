package io.turso.jdbc;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/** Value conversions between JDBC types and the storage classes returned by Turso. */
public final class Convert {
  private static final DateTimeFormatter SPACE_DATE_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

  private Convert() {}

  // ---- protocol -> Java ----

  public static String str(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof byte[] bytes) {
      return new String(bytes, StandardCharsets.UTF_8);
    }
    return value.toString();
  }

  public static long longVal(Object value) throws SQLException {
    if (value == null) {
      return 0L;
    }
    if (value instanceof Number n) {
      return n.longValue();
    }
    if (value instanceof Boolean b) {
      return b ? 1 : 0;
    }
    String s = value.toString().trim();
    if (s.isEmpty()) {
      return 0L;
    }
    try {
      return Long.parseLong(s);
    } catch (NumberFormatException e) {
      try {
        return (long) Double.parseDouble(s);
      } catch (NumberFormatException e2) {
        throw new SQLException("Cannot convert '" + s + "' to a number", "22018", e2);
      }
    }
  }

  public static double doubleVal(Object value) throws SQLException {
    if (value == null) {
      return 0d;
    }
    if (value instanceof Number n) {
      return n.doubleValue();
    }
    if (value instanceof Boolean b) {
      return b ? 1d : 0d;
    }
    String s = value.toString().trim();
    if (s.isEmpty()) {
      return 0d;
    }
    try {
      return Double.parseDouble(s);
    } catch (NumberFormatException e) {
      throw new SQLException("Cannot convert '" + s + "' to a number", "22018", e);
    }
  }

  public static boolean boolVal(Object value) throws SQLException {
    if (value == null) {
      return false;
    }
    if (value instanceof Boolean b) {
      return b;
    }
    if (value instanceof Number n) {
      return n.doubleValue() != 0d;
    }
    String s = value.toString().trim().toLowerCase(java.util.Locale.ROOT);
    return switch (s) {
      case "", "0", "false", "f", "no", "n", "off" -> false;
      case "1", "true", "t", "yes", "y", "on" -> true;
      default -> {
        try {
          yield Double.parseDouble(s) != 0d;
        } catch (NumberFormatException e) {
          throw new SQLException("Cannot convert '" + s + "' to boolean", "22018", e);
        }
      }
    };
  }

  public static BigDecimal bigDecimal(Object value) throws SQLException {
    if (value == null) {
      return null;
    }
    if (value instanceof BigDecimal d) {
      return d;
    }
    if (value instanceof BigInteger i) {
      return new BigDecimal(i);
    }
    if (value instanceof Double || value instanceof Float) {
      return BigDecimal.valueOf(((Number) value).doubleValue());
    }
    if (value instanceof Number n) {
      return BigDecimal.valueOf(n.longValue());
    }
    try {
      return new BigDecimal(value.toString().trim());
    } catch (NumberFormatException e) {
      throw new SQLException("Cannot convert '" + value + "' to decimal", "22018", e);
    }
  }

  public static byte[] bytes(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof byte[] bytes) {
      return bytes;
    }
    return value.toString().getBytes(StandardCharsets.UTF_8);
  }

  public static Timestamp timestamp(Object value) throws SQLException {
    if (value == null) {
      return null;
    }
    if (value instanceof Number n) {
      return new Timestamp(n.longValue());
    }
    String s = value.toString().trim();
    if (s.isEmpty()) {
      return null;
    }
    try {
      if (s.endsWith("Z") && s.contains("T")) {
        return Timestamp.from(Instant.parse(s));
      }
      if (s.contains("T") && (s.endsWith("Z") || s.matches(".*[+-]\\d{2}:?\\d{2}$"))) {
        return Timestamp.from(OffsetDateTime.parse(s).toInstant());
      }
      if (s.length() == 10 && s.charAt(4) == '-') {
        return Timestamp.valueOf(LocalDate.parse(s).atStartOfDay());
      }
      String iso = s.replace(' ', 'T');
      return Timestamp.valueOf(LocalDateTime.parse(iso));
    } catch (DateTimeParseException | ArithmeticException e) {
      throw new SQLException("Cannot convert '" + s + "' to timestamp", "22007", e);
    }
  }

  public static LocalDateTime localDateTime(Object value) throws SQLException {
    Timestamp ts = timestamp(value);
    return ts == null ? null : ts.toLocalDateTime();
  }

  public static LocalDate localDate(Object value) throws SQLException {
    Timestamp ts = timestamp(value);
    return ts == null ? null : ts.toLocalDateTime().toLocalDate();
  }

  public static LocalTime localTime(Object value) throws SQLException {
    Timestamp ts = timestamp(value);
    return ts == null ? null : ts.toLocalDateTime().toLocalTime();
  }

  public static Instant instant(Object value) throws SQLException {
    Timestamp ts = timestamp(value);
    return ts == null ? null : ts.toInstant();
  }

  public static OffsetDateTime offsetDateTime(Object value) throws SQLException {
    Timestamp ts = timestamp(value);
    return ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC);
  }

  @SuppressWarnings("unchecked")
  public static <T> T as(Object value, Class<T> type) throws SQLException {
    if (value == null) {
      return null;
    }
    if (type == Object.class || type.isInstance(value)) {
      return (T) value;
    }
    if (type == String.class) {
      return (T) str(value);
    }
    if (type == Long.class || type == long.class) {
      return (T) Long.valueOf(longVal(value));
    }
    if (type == Integer.class || type == int.class) {
      return (T) Integer.valueOf((int) longVal(value));
    }
    if (type == Short.class || type == short.class) {
      return (T) Short.valueOf((short) longVal(value));
    }
    if (type == Byte.class || type == byte.class) {
      return (T) Byte.valueOf((byte) longVal(value));
    }
    if (type == Double.class || type == double.class) {
      return (T) Double.valueOf(doubleVal(value));
    }
    if (type == Float.class || type == float.class) {
      return (T) Float.valueOf((float) doubleVal(value));
    }
    if (type == Boolean.class || type == boolean.class) {
      return (T) Boolean.valueOf(boolVal(value));
    }
    if (type == BigDecimal.class) {
      return (T) bigDecimal(value);
    }
    if (type == byte[].class) {
      return (T) bytes(value);
    }
    if (type == java.sql.Date.class) {
      LocalDate d = localDate(value);
      return (T) (d == null ? null : Date.valueOf(d));
    }
    if (type == Time.class) {
      LocalTime t = localTime(value);
      return (T) (t == null ? null : Time.valueOf(t));
    }
    if (type == Timestamp.class) {
      return (T) timestamp(value);
    }
    if (type == LocalDate.class) {
      return (T) localDate(value);
    }
    if (type == LocalTime.class) {
      return (T) localTime(value);
    }
    if (type == LocalDateTime.class) {
      return (T) localDateTime(value);
    }
    if (type == Instant.class) {
      return (T) instant(value);
    }
    if (type == OffsetDateTime.class) {
      return (T) offsetDateTime(value);
    }
    if (type == UUID.class) {
      return (T) UUID.fromString(str(value));
    }
    throw new SQLFeatureNotSupportedException("Conversion to " + type.getName() + " is not supported");
  }

  // ---- JDBC -> protocol ----

  public static Object toParam(Object value) throws SQLException {
    if (value == null || value instanceof String || value instanceof byte[] || value instanceof Boolean
        || value instanceof Long || value instanceof Integer || value instanceof Short
        || value instanceof Byte || value instanceof Double || value instanceof Float
        || value instanceof BigDecimal) {
      return value;
    }
    if (value instanceof Character c) {
      return c.toString();
    }
    if (value instanceof BigInteger big) {
      return big.bitLength() < 64 ? (Object) big.longValue() : big.toString();
    }
    if (value instanceof Date d) {
      return d.toString();
    }
    if (value instanceof Time t) {
      return t.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));
    }
    if (value instanceof Timestamp ts) {
      return ts.toLocalDateTime().format(SPACE_DATE_TIME);
    }
    if (value instanceof LocalDate ld) {
      return ld.toString();
    }
    if (value instanceof LocalTime lt) {
      return lt.format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));
    }
    if (value instanceof LocalDateTime ldt) {
      return ldt.format(SPACE_DATE_TIME);
    }
    if (value instanceof Instant instant) {
      return instant.toString();
    }
    if (value instanceof OffsetDateTime odt) {
      return odt.toString();
    }
    if (value instanceof UUID uuid) {
      return uuid.toString();
    }
    if (value instanceof Blob blob) {
      return blob.getBytes(1, (int) blob.length());
    }
    if (value instanceof Clob clob) {
      return clob.getSubString(1, (int) clob.length());
    }
    if (value instanceof java.io.InputStream in) {
      try {
        return in.readAllBytes();
      } catch (java.io.IOException e) {
        throw new SQLException("Failed to read binary stream", e);
      }
    }
    if (value instanceof java.io.Reader reader) {
      StringBuilder sb = new StringBuilder();
      char[] buf = new char[4096];
      int n;
      try {
        while ((n = reader.read(buf)) >= 0) {
          sb.append(buf, 0, n);
        }
      } catch (java.io.IOException e) {
        throw new SQLException("Failed to read character stream", e);
      }
      return sb.toString();
    }
    throw new SQLFeatureNotSupportedException("Unsupported bind value: " + value.getClass().getName());
  }

  public static int sqlTypeOf(Object value) {
    if (value == null) {
      return Types.NULL;
    }
    if (value instanceof String || value instanceof Character) {
      return Types.VARCHAR;
    }
    if (value instanceof Boolean) {
      return Types.BOOLEAN;
    }
    if (value instanceof Long || value instanceof Integer || value instanceof Short
        || value instanceof Byte || value instanceof BigInteger) {
      return Types.BIGINT;
    }
    if (value instanceof Double || value instanceof Float) {
      return Types.DOUBLE;
    }
    if (value instanceof BigDecimal) {
      return Types.NUMERIC;
    }
    if (value instanceof byte[]) {
      return Types.BLOB;
    }
    if (value instanceof Date || value instanceof LocalDate) {
      return Types.DATE;
    }
    if (value instanceof Time || value instanceof LocalTime) {
      return Types.TIME;
    }
    if (value instanceof Timestamp || value instanceof LocalDateTime || value instanceof Instant
        || value instanceof OffsetDateTime) {
      return Types.TIMESTAMP;
    }
    return Types.OTHER;
  }
}

package io.turso.jdbc;

import java.sql.Types;
import java.util.Locale;

/** Maps SQLite declared types to JDBC types. */
public final class TypeMapper {
  private TypeMapper() {}

  public static int sqlType(String decltype) {
    if (decltype == null || decltype.isBlank()) {
      return Types.OTHER;
    }
    String type = decltype.trim().toUpperCase(Locale.ROOT);
    int paren = type.indexOf('(');
    if (paren > 0) {
      type = type.substring(0, paren).trim();
    }
    return switch (type) {
      case "INT", "INTEGER", "BIGINT", "TINYINT", "SMALLINT", "MEDIUMINT", "INT2", "INT8",
          "UNSIGNED BIG INT" -> Types.BIGINT;
      case "REAL", "DOUBLE", "DOUBLE PRECISION", "FLOAT" -> Types.DOUBLE;
      case "NUMERIC", "DECIMAL" -> Types.NUMERIC;
      case "BOOLEAN", "BOOL" -> Types.BOOLEAN;
      case "BLOB" -> Types.BLOB;
      case "DATE" -> Types.DATE;
      case "TIME" -> Types.TIME;
      case "DATETIME", "TIMESTAMP" -> Types.TIMESTAMP;
      case "TEXT", "VARCHAR", "VARYING CHARACTER", "CHAR", "CHARACTER", "CLOB", "STRING",
          "NCHAR", "NVARCHAR", "NATIVE CHARACTER" -> Types.VARCHAR;
      default -> Types.OTHER;
    };
  }

  public static String className(int sqlType) {
    return switch (sqlType) {
      case Types.BIGINT -> Long.class.getName();
      case Types.DOUBLE -> Double.class.getName();
      case Types.NUMERIC, Types.DECIMAL -> java.math.BigDecimal.class.getName();
      case Types.BOOLEAN -> Boolean.class.getName();
      case Types.BLOB, Types.BINARY, Types.VARBINARY -> byte[].class.getName();
      case Types.DATE -> java.sql.Date.class.getName();
      case Types.TIME -> java.sql.Time.class.getName();
      case Types.TIMESTAMP -> java.sql.Timestamp.class.getName();
      case Types.VARCHAR -> String.class.getName();
      default -> Object.class.getName();
    };
  }

  public static boolean isSigned(int sqlType) {
    return sqlType == Types.BIGINT || sqlType == Types.DOUBLE || sqlType == Types.NUMERIC
        || sqlType == Types.DECIMAL || sqlType == Types.FLOAT || sqlType == Types.REAL;
  }

  public static boolean isNumeric(int sqlType) {
    return sqlType == Types.BIGINT || sqlType == Types.DOUBLE || sqlType == Types.NUMERIC
        || sqlType == Types.DECIMAL || sqlType == Types.FLOAT || sqlType == Types.REAL
        || sqlType == Types.INTEGER || sqlType == Types.SMALLINT || sqlType == Types.TINYINT;
  }
}

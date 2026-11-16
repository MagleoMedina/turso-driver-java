package io.turso.jdbc.protocol;

import java.sql.BatchUpdateException;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLNonTransientException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransactionRollbackException;
import java.sql.SQLTransientConnectionException;
import java.util.Map;

/** Maps Turso protocol and HTTP failures to the most specific JDBC exceptions. */
public final class TursoErrors {
  private TursoErrors() {}

  /** Maps an in-band protocol error object (section 9.1). */
  public static SQLException protocol(Object rawError, String sql) {
    String message = "SQL execution failed";
    String code = null;
    String extended = null;
    if (rawError != null && rawError instanceof Map<?, ?> error) {
      message = text(error.get("message"), message);
      code = Json.string(error.get("code"));
      extended = Json.string(error.get("extended_code"));
    }
    String state = sqlState(code);
    String full = decorate(message, code, extended, sql);
    int vendor = vendorCode(code);
    return switch (state) {
      case "40001" -> new SQLTransactionRollbackException(full, state, vendor);
      case "42601" -> new SQLSyntaxErrorException(full, state, vendor);
      case "28000" -> new SQLInvalidAuthorizationSpecException(full, state, vendor);
      case "57014" -> new SQLTimeoutException(full, state, vendor);
      case "23000" -> new SQLIntegrityConstraintViolationException(full, state, vendor);
      case "25006", "42501" -> new SQLNonTransientException(full, state, vendor);
      case "08006" -> new SQLTransientConnectionException(full, state, vendor);
      default -> new SQLException(full, state, vendor);
    };
  }

  public static SQLException http(int status, String body, String sql) {
    String state;
    String message;
    switch (status) {
      case 401 -> {
        state = "28000";
        message = "Authentication failed (401). Check the Turso auth token.";
      }
      case 403 -> {
        state = "42501";
        message = "Access denied (403).";
      }
      case 404 -> {
        state = "08001";
        message = "Not found (404): unknown database, endpoint or expired stream.";
      }
      case 408 -> {
        state = "57014";
        message = "Request timed out (408).";
      }
      case 429 -> {
        state = "40001";
        message = "Rate limited (429). Retry later.";
      }
      case 500, 503 -> {
        state = "08006";
        message = "Server unavailable (" + status + ").";
      }
      default -> {
        state = "HY000";
        message = "HTTP " + status + ".";
      }
    }
    String detail = errorMessage(body);
    String full = detail == null ? message : message + " " + detail;
    if (sql != null && !sql.isBlank()) {
      full = full + " [SQL: " + abbreviate(sql) + "]";
    }
    return switch (state) {
      case "28000" -> new SQLInvalidAuthorizationSpecException(full, state, status);
      case "08001", "08006" -> new SQLNonTransientConnectionException(full, state, status);
      case "40001" -> new SQLTransactionRollbackException(full, state, status);
      case "57014" -> new SQLTimeoutException(full, state, status);
      case "42501" -> new SQLNonTransientException(full, state, status);
      default -> new SQLException(full, state, status);
    };
  }

  public static SQLException streamExpired(String message) {
    return new SQLTransactionRollbackException(
        message + " The stream expired and any open transaction was rolled back.",
        "40001", 5);
  }

  public static SQLException truncated(String message) {
    return new SQLTransientConnectionException(message, "08006", 0);
  }

  public static SQLException io(String message, Throwable cause) {
    return new SQLTransientConnectionException(message, "08006", cause);
  }

  public static BatchUpdateException batch(String message, String state, SQLException cause,
      long[] counts) {
    int[] ints = new int[counts.length];
    for (int i = 0; i < counts.length; i++) {
      ints[i] = counts[i] > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) counts[i];
    }
    return new BatchUpdateException(message, state, ints, cause);
  }

  public static String sqlState(String code) {
    if (code == null) {
      return "HY000";
    }
    return switch (code) {
      case "SQLITE_CONSTRAINT" -> "23000";
      case "SQLITE_BUSY", "SQLITE_LOCKED" -> "40001";
      case "SQL_PARSE_ERROR", "SQL_INPUT_ERROR", "SQL_MANY_STATEMENTS" -> "42601";
      case "SQLITE_READONLY" -> "25006";
      case "SQLITE_PERM", "SQLITE_AUTH" -> "42501";
      case "SQLITE_INTERRUPT" -> "57014";
      case "SQLITE_TOOBIG" -> "54000";
      case "SQLITE_RANGE" -> "22003";
      case "SQLITE_MISMATCH" -> "22018";
      case "SQLITE_IOERR", "SQLITE_CORRUPT", "SQLITE_NOTADB", "SQLITE_CANTOPEN" -> "58030";
      case "SQLITE_NOMEM", "SQLITE_FULL" -> "53100";
      case "SQLITE_INTERNAL" -> "XX000";
      default -> "42000";
    };
  }

  private static int vendorCode(String code) {
    if (code == null) {
      return 0;
    }
    return switch (code) {
      case "SQLITE_INTERNAL" -> 2;
      case "SQLITE_PERM" -> 3;
      case "SQLITE_BUSY" -> 5;
      case "SQLITE_LOCKED" -> 6;
      case "SQLITE_NOMEM" -> 7;
      case "SQLITE_READONLY" -> 8;
      case "SQLITE_INTERRUPT" -> 9;
      case "SQLITE_IOERR" -> 10;
      case "SQLITE_CORRUPT" -> 11;
      case "SQLITE_FULL" -> 13;
      case "SQLITE_CANTOPEN" -> 14;
      case "SQLITE_PROTOCOL" -> 15;
      case "SQLITE_SCHEMA" -> 17;
      case "SQLITE_TOOBIG" -> 18;
      case "SQLITE_CONSTRAINT" -> 19;
      case "SQLITE_MISMATCH" -> 20;
      case "SQLITE_MISUSE" -> 21;
      case "SQLITE_AUTH" -> 23;
      case "SQLITE_RANGE" -> 25;
      case "SQLITE_NOTADB" -> 26;
      case "SQL_PARSE_ERROR", "SQL_INPUT_ERROR", "SQL_MANY_STATEMENTS" -> 1;
      default -> 0;
    };
  }

  private static String decorate(String message, String code, String extended, String sql) {
    StringBuilder sb = new StringBuilder();
    if (code != null) {
      sb.append(code);
      if (extended != null) {
        sb.append(" (").append(extended).append(')');
      }
      sb.append(": ");
    }
    sb.append(message);
    if (sql != null && !sql.isBlank()) {
      sb.append(" [SQL: ").append(abbreviate(sql)).append(']');
    }
    return sb.toString();
  }

  static String abbreviate(String sql) {
    String oneLine = sql.replaceAll("\\s+", " ").trim();
    return oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 197) + "...";
  }

  private static String text(Object value, String fallback) {
    return value == null ? fallback : value.toString();
  }

  static String errorMessage(String body) {
    if (body == null || body.isBlank()) {
      return null;
    }
    try {
      Object parsed = Json.parse(body);
      if (parsed instanceof Map<?, ?> map) {
        Object err = map.get("error");
        if (err != null) {
          return err.toString();
        }
        Object msg = map.get("message");
        if (msg != null) {
          return msg.toString();
        }
      }
    } catch (RuntimeException ignored) {
      // not JSON
    }
    return abbreviate(body);
  }
}

package io.turso.jdbc;

import java.util.Locale;
import java.util.Set;

/** Small SQL helpers. */
final class Sql {
  private static final Set<String> TRANSACTION_CONTROL =
      Set.of("BEGIN", "COMMIT", "END", "ROLLBACK", "SAVEPOINT", "RELEASE");

  private Sql() {}

  /** First keyword of a statement, skipping whitespace, comments and empty statements. */
  static String firstKeyword(String sql) {
    if (sql == null) {
      return null;
    }
    int i = 0;
    int len = sql.length();
    while (i < len) {
      char c = sql.charAt(i);
      if (Character.isWhitespace(c) || c == ';') {
        i++;
        continue;
      }
      if (sql.startsWith("--", i)) {
        int nl = sql.indexOf('\n', i + 2);
        if (nl < 0) {
          return null;
        }
        i = nl + 1;
        continue;
      }
      if (sql.startsWith("/*", i)) {
        int end = sql.indexOf("*/", i + 2);
        if (end < 0) {
          return null;
        }
        i = end + 2;
        continue;
      }
      break;
    }
    int start = i;
    while (i < len) {
      char c = sql.charAt(i);
      if (Character.isLetter(c) || c == '_') {
        i++;
      } else {
        break;
      }
    }
    if (start == i) {
      return null;
    }
    return sql.substring(start, i).toUpperCase(Locale.ROOT);
  }

  static boolean isTransactionControl(String sql) {
    String keyword = firstKeyword(sql);
    return keyword != null && TRANSACTION_CONTROL.contains(keyword);
  }

  static String quoteIdentifier(String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Empty identifier");
    }
    if (name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
      return name;
    }
    return "\"" + name.replace("\"", "\"\"") + "\"";
  }
}

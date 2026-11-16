package io.turso.jdbc;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Locale;
import java.util.Properties;

/** Parsed JDBC URL and connection properties. */
public record TursoConfig(
    String jdbcUrl,
    String url,
    String token,
    String encryptionKey,
    String transactionMode,
    Duration connectTimeout,
    Duration requestTimeout,
    boolean readOnly) {

  public static final String PREFIX = "jdbc:turso:";

  public static TursoConfig parse(String jdbcUrl, Properties info) throws SQLException {
    if (jdbcUrl == null || !jdbcUrl.startsWith(PREFIX)) {
      throw new SQLException("Invalid Turso JDBC URL: " + jdbcUrl);
    }
    String rest = jdbcUrl.substring(PREFIX.length());
    String query = null;
    int q = rest.indexOf('?');
    if (q >= 0) {
      query = rest.substring(q + 1);
      rest = rest.substring(0, q);
    }
    String base;
    if (rest.startsWith("//")) {
      base = "https:" + rest;
    } else if (rest.startsWith("libsql://") || rest.startsWith("turso://")) {
      base = "https://" + rest.substring(rest.indexOf("://") + 3);
    } else if (rest.startsWith("http://") || rest.startsWith("https://")) {
      base = rest;
    } else {
      base = "https://" + rest;
    }
    base = io.turso.jdbc.protocol.Session.normalizeUrl(base);
    try {
      if (java.net.URI.create(base).getHost() == null) {
        throw new SQLException("Turso JDBC URL is missing the database host: " + jdbcUrl);
      }
    } catch (IllegalArgumentException e) {
      throw new SQLException("Invalid Turso JDBC URL: " + jdbcUrl, e);
    }

    Properties props = new Properties();
    if (info != null) {
      props.putAll(info);
    }
    if (query != null && !query.isBlank()) {
      for (String pair : query.split("&")) {
        if (pair.isBlank()) {
          continue;
        }
        int eq = pair.indexOf('=');
        String key = decode(eq < 0 ? pair : pair.substring(0, eq));
        String value = eq < 0 ? "true" : decode(pair.substring(eq + 1));
        props.setProperty(key, value);
      }
    }

    String token = firstNonBlank(props.getProperty("authToken"), props.getProperty("token"),
        props.getProperty("password"));
    String encryptionKey = firstNonBlank(props.getProperty("encryptionKey"),
        props.getProperty("encryption_key"));
    String mode = firstNonBlank(props.getProperty("transactionMode"), props.getProperty("transaction_mode"));
    mode = mode == null ? "DEFERRED" : mode.trim().toUpperCase(Locale.ROOT);
    if (!mode.equals("DEFERRED") && !mode.equals("IMMEDIATE") && !mode.equals("EXCLUSIVE")
        && !mode.equals("CONCURRENT")) {
      throw new SQLException("Unsupported transactionMode: " + mode);
    }

    long connectMs = parseLong(props, "connectTimeoutMs", 10_000L);
    long requestMs = parseLong(props, "requestTimeoutMs", 30_000L);
    if (connectMs <= 0 || requestMs <= 0) {
      throw new SQLException("Timeout values must be positive");
    }
    boolean readOnly = Boolean.parseBoolean(props.getProperty("readOnly", "false"));

    return new TursoConfig(jdbcUrl, base, token, encryptionKey, mode,
        Duration.ofMillis(connectMs), Duration.ofMillis(requestMs), readOnly);
  }

  private static long parseLong(Properties props, String key, long fallback) throws SQLException {
    String value = props.getProperty(key);
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      return Long.parseLong(value.trim());
    } catch (NumberFormatException e) {
      throw new SQLException("Invalid " + key + ": " + value);
    }
  }

  private static String decode(String value) {
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }
}

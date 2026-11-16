package io.turso.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.turso.jdbc.protocol.Json;
import io.turso.jdbc.protocol.Values;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Importa un dump de SQLite (tools/export_sqlite.py) en la base Turso configurada y compara
 * filas y un checksum SHA-256. Solo se ejecuta con -Dcierre.dump=/ruta/dump.json.
 */
@Tag("integration")
class CierreImportIT {
  private static final int BATCH = 50;

  @Test
  void importDumpAndVerify() throws Exception {
    String dumpPath = System.getProperty("cierre.dump", System.getenv("CIERRE_DUMP"));
    assumeTrue(dumpPath != null && !dumpPath.isBlank(),
        "Set -Dcierre.dump=/path/to/dump.json (or CIERRE_DUMP) to import a SQLite dump");
    String url = System.getenv("TURSO_URL");
    String token = System.getenv("TURSO_TOKEN");
    assumeTrue(url != null && token != null, "Set TURSO_URL and TURSO_TOKEN");

    Map<String, Object> dump = Json.object(Json.parse(
        Files.readString(Path.of(dumpPath), StandardCharsets.UTF_8)));
    List<Object> tables = Json.array(dump.get("tables"));

    Properties properties = new Properties();
    properties.setProperty("authToken", token);
    try (Connection connection = DriverManager.getConnection("jdbc:turso:" + url, properties)) {
      exec(connection, "PRAGMA foreign_keys=OFF");
      for (int i = tables.size() - 1; i >= 0; i--) {
        exec(connection, "DROP TABLE IF EXISTS " + Sql.quoteIdentifier(name(tables.get(i))));
      }
      for (Object table : tables) {
        exec(connection, Json.string(Json.object(table).get("sql")));
      }
      for (Object sql : Json.array(dump.get("otherSchema"))) {
        exec(connection, Json.string(sql));
      }

      for (Object table : tables) {
        importTable(connection, Json.object(table));
      }
      exec(connection, "PRAGMA foreign_keys=ON");

      for (Object table : tables) {
        verifyTable(connection, Json.object(table));
      }
      if (tables.stream().anyMatch(t -> "reporte".equals(name(t)))) {
        assertTypedAccess(connection);
      }
    }
  }

  /** Comprueba conversiones tipadas sobre datos reales de la app (timestamps epoch, decimales, UUID). */
  private void assertTypedAccess(Connection connection) throws Exception {
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery(
            "SELECT created_at, fecha, ingresos_totales, client_id FROM reporte ORDER BY id LIMIT 1")) {
      assertTrue(rs.next());
      assertTrue(rs.getTimestamp("created_at") != null);
      assertTrue(rs.getTimestamp("fecha") != null);
      assertTrue(rs.getBigDecimal("ingresos_totales") != null);
      assertTrue(rs.getObject("client_id", java.util.UUID.class) != null);
    }
  }

  private void importTable(Connection connection, Map<String, Object> table) throws Exception {
    String name = Json.string(table.get("name"));
    List<Object> columns = Json.array(table.get("columns"));
    List<Object> rows = Json.array(table.get("rows"));
    if (rows.isEmpty()) {
      return;
    }
    StringBuilder sql = new StringBuilder("INSERT INTO ")
        .append(Sql.quoteIdentifier(name)).append(" (");
    for (int i = 0; i < columns.size(); i++) {
      if (i > 0) {
        sql.append(", ");
      }
      sql.append(Sql.quoteIdentifier(Json.string(columns.get(i))));
    }
    sql.append(") VALUES (");
    sql.append("?, ".repeat(columns.size() - 1)).append("?)");

    try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
      int pending = 0;
      for (Object rawRow : rows) {
        List<Object> row = Json.array(rawRow);
        for (int i = 0; i < row.size(); i++) {
          statement.setObject(i + 1, decodeBlob(row.get(i)));
        }
        statement.addBatch();
        if (++pending == BATCH) {
          statement.executeBatch();
          pending = 0;
        }
      }
      if (pending > 0) {
        statement.executeBatch();
      }
    }
  }

  private void verifyTable(Connection connection, Map<String, Object> table) throws Exception {
    String name = Json.string(table.get("name"));
    List<Object> columns = Json.array(table.get("columns"));
    List<Object> rows = Json.array(table.get("rows"));
    String orderBy = Json.string(table.get("orderBy"));

    long expected = rows.size();
    long actual = count(connection, name);
    assertEquals(expected, actual, "row count for " + name);

    String expectedHash = hash(expected, columns.size(), rows);
    String actualHash = hashRemote(connection, name, columns, orderBy);
    System.out.printf("%-16s rows=%d sha256=%s%n", name, actual, actualHash);
    assertEquals(expectedHash, actualHash, "content hash for " + name);
    assertSample(connection, name, columns);
  }

  private String hashRemote(Connection connection, String name, List<Object> columns,
      String orderBy) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    StringBuilder sql = new StringBuilder("SELECT ");
    for (int i = 0; i < columns.size(); i++) {
      if (i > 0) {
        sql.append(", ");
      }
      sql.append(Sql.quoteIdentifier(Json.string(columns.get(i))));
    }
    sql.append(" FROM ").append(Sql.quoteIdentifier(name)).append(" ORDER BY ").append(orderBy);
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery(sql.toString())) {
      while (rs.next()) {
        for (int i = 1; i <= columns.size(); i++) {
          canonical(digest, rs.getObject(i));
        }
        digest.update((byte) '\n');
      }
    }
    return hex(digest.digest());
  }

  private String hash(long expected, int columnCount, List<Object> rows) throws Exception {
    assertEquals(expected, rows.size());
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    for (Object rawRow : rows) {
      List<Object> row = Json.array(rawRow);
      assertEquals(columnCount, row.size());
      for (Object value : row) {
        canonical(digest, decodeBlob(value));
      }
      digest.update((byte) '\n');
    }
    return hex(digest.digest());
  }

  private static void canonical(MessageDigest digest, Object value) {
    if (value == null) {
      digest.update("N|".getBytes(StandardCharsets.UTF_8));
    } else if (value instanceof byte[] bytes) {
      digest.update(("B:" + Base64.getEncoder().encodeToString(bytes) + "|")
          .getBytes(StandardCharsets.UTF_8));
    } else if (value instanceof Number number) {
      if (value instanceof Double || value instanceof Float) {
        digest.update(("R:" + Double.toString(number.doubleValue()) + "|")
            .getBytes(StandardCharsets.UTF_8));
      } else {
        digest.update(("I:" + number.longValue() + "|").getBytes(StandardCharsets.UTF_8));
      }
    } else {
      digest.update(("S:" + value + "|").getBytes(StandardCharsets.UTF_8));
    }
  }

  private static Object decodeBlob(Object value) {
    if (value instanceof Map<?, ?> map && map.containsKey("__blob__")) {
      return Values.decodeBlob(Json.string(map.get("__blob__")));
    }
    return value;
  }

  private void assertSample(Connection connection, String name, List<Object> columns)
      throws Exception {
    StringBuilder sql = new StringBuilder("SELECT ");
    for (int i = 0; i < columns.size(); i++) {
      if (i > 0) {
        sql.append(", ");
      }
      sql.append(Sql.quoteIdentifier(Json.string(columns.get(i))));
    }
    sql.append(" FROM ").append(Sql.quoteIdentifier(name)).append(" LIMIT 1");
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery(sql.toString())) {
      assertTrue(rs.next(), "sample row for " + name);
      List<String> values = new ArrayList<>();
      for (int i = 1; i <= columns.size(); i++) {
        Object value = rs.getObject(i);
        values.add(Json.string(columns.get(i - 1)) + "="
            + (value instanceof byte[] bytes ? "blob(" + bytes.length + ")" : value));
      }
      System.out.println("  " + name + ": " + String.join(", ", values));
    }
  }

  private long count(Connection connection, String name) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery(
            "SELECT count(*) FROM " + Sql.quoteIdentifier(name))) {
      assertTrue(rs.next());
      return rs.getLong(1);
    }
  }

  private static void exec(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static String name(Object table) {
    return Json.string(Json.object(table).get("name"));
  }

  private static String hex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }
}

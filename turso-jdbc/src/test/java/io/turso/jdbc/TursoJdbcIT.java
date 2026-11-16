package io.turso.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Runs against a real Turso Cloud database when TURSO_URL and TURSO_TOKEN are set. */
@Tag("integration")
class TursoJdbcIT {
  private static String jdbcUrl;
  private static String token;
  private Connection connection;

  @BeforeAll
  static void requireCredentials() {
    String url = System.getenv("TURSO_URL");
    token = System.getenv("TURSO_TOKEN");
    assumeTrue(url != null && !url.isBlank() && token != null && !token.isBlank(),
        "Set TURSO_URL and TURSO_TOKEN to run integration tests");
    jdbcUrl = "jdbc:turso:" + url;
  }

  @BeforeEach
  void open() throws Exception {
    Properties properties = new Properties();
    properties.setProperty("authToken", token);
    connection = DriverManager.getConnection(jdbcUrl, properties);
    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate("""
          CREATE TABLE IF NOT EXISTS it_turso_jdbc (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            score REAL,
            flag BOOLEAN,
            data BLOB,
            created_at TIMESTAMP
          )""");
      statement.executeUpdate("DELETE FROM it_turso_jdbc");
    }
  }

  @AfterEach
  void close() throws Exception {
    if (connection != null) {
      connection.close();
    }
  }

  @Test
  void metadataAndValidity() throws Exception {
    DatabaseMetaData meta = connection.getMetaData();
    assertEquals("SQLite", meta.getDatabaseProductName());
    assertNotNull(meta.getDatabaseProductVersion());
    assertEquals("Turso JDBC Driver", meta.getDriverName());
    assertEquals(jdbcUrl, meta.getURL());
    assertTrue(connection.isValid(5));
    assertEquals(Connection.TRANSACTION_SERIALIZABLE, connection.getTransactionIsolation());
  }

  @Test
  void insertUpdateAndGeneratedKeys() throws Exception {
    try (Statement statement = connection.createStatement()) {
      assertEquals(1, statement.executeLargeUpdate(
          "INSERT INTO it_turso_jdbc(name, score, flag, created_at) "
              + "VALUES ('a', 1.5, 1, '2024-01-02 03:04:05.123')"));
      try (ResultSet keys = statement.getGeneratedKeys()) {
        assertTrue(keys.next());
        assertTrue(keys.getLong(1) > 0);
      }
      assertEquals(1, statement.executeUpdate("UPDATE it_turso_jdbc SET score = 2.5 WHERE name = 'a'"));
      try (ResultSet rs = statement.executeQuery("SELECT score FROM it_turso_jdbc WHERE name = 'a'")) {
        assertTrue(rs.next());
        assertEquals(2.5d, rs.getDouble(1));
      }
    }
  }

  @Test
  void preparedParametersMetadataAndTypes() throws Exception {
    byte[] payload = {1, 2, 3, 4};
    Timestamp createdAt = Timestamp.valueOf("2024-05-06 07:08:09.1");
    try (PreparedStatement insert = connection.prepareStatement(
        "INSERT INTO it_turso_jdbc(name, score, flag, data, created_at) VALUES (?, ?, ?, ?, ?)")) {
      insert.setString(1, "prepared");
      insert.setDouble(2, 9.25);
      insert.setBoolean(3, true);
      insert.setBytes(4, payload);
      insert.setTimestamp(5, createdAt);
      assertEquals(1, insert.executeUpdate());
    }

    try (PreparedStatement select = connection.prepareStatement(
        "SELECT id, name, score, flag, data, created_at FROM it_turso_jdbc WHERE name = ?")) {
      select.setString(1, "prepared");
      ResultSetMetaData meta = select.getMetaData();
      assertEquals(6, meta.getColumnCount());
      assertEquals("name", meta.getColumnLabel(2));
      assertEquals(1, select.getParameterMetaData().getParameterCount());
      try (ResultSet rs = select.executeQuery()) {
        assertTrue(rs.next());
        assertEquals("prepared", rs.getString("name"));
        assertEquals(9.25d, rs.getDouble("score"));
        assertTrue(rs.getBoolean("flag"));
        assertArrayEquals(payload, rs.getBytes("data"));
        assertEquals(createdAt, rs.getTimestamp("created_at"));
        assertTrue(rs.getObject("created_at", java.time.LocalDateTime.class) != null);
      }
    }
  }

  @Test
  void streamingAndMaxRows() throws Exception {
    try (PreparedStatement insert = connection.prepareStatement(
        "INSERT INTO it_turso_jdbc(name) VALUES (?)")) {
      for (int i = 0; i < 200; i++) {
        insert.setString(1, "row-" + i);
        insert.addBatch();
      }
      int[] counts = insert.executeBatch();
      assertEquals(200, counts.length);
      assertEquals(1, counts[199]);
    }
    try (Statement statement = connection.createStatement()) {
      statement.setMaxRows(10);
      try (ResultSet rs = statement.executeQuery("SELECT id FROM it_turso_jdbc")) {
        int rows = 0;
        while (rs.next()) {
          rows++;
        }
        assertEquals(10, rows);
      }
    }
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT count(*) FROM it_turso_jdbc")) {
      assertTrue(rs.next());
      assertEquals(200, rs.getInt(1));
    }
  }

  @Test
  void transactionsAndSavepoints() throws Exception {
    connection.setAutoCommit(false);
    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate("INSERT INTO it_turso_jdbc(name) VALUES ('rolled-back')");
    }
    connection.rollback();
    connection.setAutoCommit(true);
    assertEquals(0, count());

    connection.setAutoCommit(false);
    Savepoint savepoint;
    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate("INSERT INTO it_turso_jdbc(name) VALUES ('kept')");
      savepoint = connection.setSavepoint();
      statement.executeUpdate("INSERT INTO it_turso_jdbc(name) VALUES ('dropped')");
      connection.rollback(savepoint);
      connection.releaseSavepoint(savepoint);
      statement.executeUpdate("INSERT INTO it_turso_jdbc(name) VALUES ('also-kept')");
    }
    connection.commit();
    connection.setAutoCommit(true);
    assertEquals(2, count());
  }

  @Test
  void atomicBatchRollsBackOnFailure() throws Exception {
    try (Statement statement = connection.createStatement()) {
      statement.addBatch("INSERT INTO it_turso_jdbc(id, name) VALUES (1, 'a')");
      statement.addBatch("INSERT INTO it_turso_jdbc(id, name) VALUES (1, 'b')");
      assertThrows(BatchUpdateException.class, statement::executeBatch);
    }
    assertEquals(0, count());
  }

  @Test
  void constraintViolationsMapToJdbcTypes() throws Exception {
    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate("INSERT INTO it_turso_jdbc(id, name) VALUES (1, 'a')");
      SQLException error = assertThrows(SQLException.class,
          () -> statement.executeUpdate("INSERT INTO it_turso_jdbc(id, name) VALUES (1, 'b')"));
      assertTrue(error instanceof SQLIntegrityConstraintViolationException,
          "Expected constraint violation, got " + error.getClass());
      assertEquals("23000", error.getSQLState());
    }
  }

  @Test
  void tempTablesKeepTheSession() throws Exception {
    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate("CREATE TEMP TABLE IF NOT EXISTS it_tmp(a INTEGER)");
      statement.executeUpdate("INSERT INTO it_tmp VALUES (7)");
    }
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT a FROM it_tmp")) {
      assertTrue(rs.next());
      assertEquals(7, rs.getInt(1));
    }
  }

  @Test
  void namedParametersAndInt64Range() throws Exception {
    try (PreparedStatement statement = connection.prepareStatement("SELECT :a AS a, :b AS b, ? AS c")) {
      statement.setInt(1, 7);
      statement.setString(2, "named");
      statement.setLong(3, Long.MIN_VALUE);
      try (ResultSet rs = statement.executeQuery()) {
        assertTrue(rs.next());
        assertEquals(7, rs.getInt("a"));
        assertEquals("named", rs.getString("b"));
        assertEquals(Long.MIN_VALUE, rs.getLong("c"));
      }
    }
  }

  @Test
  void maxRowsInsideTransaction() throws Exception {
    try (PreparedStatement insert = connection.prepareStatement(
        "INSERT INTO it_turso_jdbc(name) VALUES (?)")) {
      for (int i = 0; i < 50; i++) {
        insert.setString(1, "tx-" + i);
        insert.addBatch();
      }
      insert.executeBatch();
    }
    connection.setAutoCommit(false);
    try (Statement statement = connection.createStatement()) {
      statement.setMaxRows(5);
      try (ResultSet rs = statement.executeQuery("SELECT id FROM it_turso_jdbc")) {
        int rows = 0;
        while (rs.next()) {
          rows++;
        }
        assertEquals(5, rows);
      }
    }
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT count(*) FROM it_turso_jdbc")) {
      assertTrue(rs.next());
      assertEquals(50, rs.getInt(1));
    }
    connection.commit();
    connection.setAutoCommit(true);
  }

  private int count() throws Exception {
    try (Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery("SELECT count(*) FROM it_turso_jdbc")) {
      assertTrue(rs.next());
      return rs.getInt(1);
    }
  }
}

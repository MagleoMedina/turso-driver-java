package io.turso.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class TursoConfigTest {

  @Test
  void normalizesLibsqlUrl() throws Exception {
    TursoConfig config = TursoConfig.parse("jdbc:turso:libsql://db-org.turso.io", new Properties());
    assertEquals("https://db-org.turso.io", config.url());
    assertEquals("DEFERRED", config.transactionMode());
    assertEquals(Duration.ofSeconds(30), config.requestTimeout());
  }

  @Test
  void acceptsHttpsAndBareHost() throws Exception {
    assertEquals("https://db.turso.io",
        TursoConfig.parse("jdbc:turso:https://db.turso.io", new Properties()).url());
    assertEquals("https://db.turso.io",
        TursoConfig.parse("jdbc:turso:db.turso.io", new Properties()).url());
    assertEquals("https://db.turso.io",
        TursoConfig.parse("jdbc:turso://db.turso.io", new Properties()).url());
  }

  @Test
  void readsQueryParametersAndOverridesProperties() throws Exception {
    Properties props = new Properties();
    props.setProperty("authToken", "fromProps");
    TursoConfig config = TursoConfig.parse(
        "jdbc:turso:db.turso.io?authToken=fromUrl&transactionMode=immediate&requestTimeoutMs=1000",
        props);
    assertEquals("fromUrl", config.token());
    assertEquals("IMMEDIATE", config.transactionMode());
    assertEquals(Duration.ofMillis(1000), config.requestTimeout());
  }

  @Test
  void tokenFallsBackToPassword() throws Exception {
    Properties props = new Properties();
    props.setProperty("password", "secret");
    assertEquals("secret", TursoConfig.parse("jdbc:turso:x.turso.io", props).token());
  }

  @Test
  void rejectsInvalidMode() {
    assertThrows(SQLException.class,
        () -> TursoConfig.parse("jdbc:turso:x.turso.io?transactionMode=nope", new Properties()));
  }

  @Test
  void rejectsMissingHost() {
    assertThrows(SQLException.class, () -> TursoConfig.parse("jdbc:turso:", new Properties()));
  }

  @Test
  void noTokenIsNull() throws Exception {
    assertNull(TursoConfig.parse("jdbc:turso:x.turso.io", new Properties()).token());
  }
}

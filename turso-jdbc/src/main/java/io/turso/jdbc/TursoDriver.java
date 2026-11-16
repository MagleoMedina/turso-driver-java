package io.turso.jdbc;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Properties;
import java.util.logging.Logger;

/** JDBC driver for Turso Cloud over the SQL over HTTP v3 protocol. */
public final class TursoDriver implements Driver {
  public static final int VERSION_MAJOR = 0;
  public static final int VERSION_MINOR = 1;

  static {
    try {
      DriverManager.registerDriver(new TursoDriver());
    } catch (SQLException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @Override
  public Connection connect(String url, Properties info) throws SQLException {
    if (!acceptsURL(url)) {
      return null;
    }
    return new TursoConnection(TursoConfig.parse(url, info));
  }

  @Override
  public boolean acceptsURL(String url) {
    return url != null && url.startsWith(TursoConfig.PREFIX);
  }

  @Override
  public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
    DriverPropertyInfo token = new DriverPropertyInfo("authToken",
        info == null ? null : info.getProperty("authToken"));
    token.description = "Turso database auth token (alternatively pass it as the JDBC password)";
    DriverPropertyInfo mode = new DriverPropertyInfo("transactionMode", "DEFERRED");
    mode.description = "BEGIN mode: DEFERRED, IMMEDIATE, EXCLUSIVE or CONCURRENT";
    mode.choices = new String[] {"DEFERRED", "IMMEDIATE", "EXCLUSIVE", "CONCURRENT"};
    DriverPropertyInfo timeout = new DriverPropertyInfo("requestTimeoutMs", "30000");
    timeout.description = "HTTP request timeout in milliseconds";
    return new DriverPropertyInfo[] {token, mode, timeout};
  }

  @Override
  public int getMajorVersion() {
    return VERSION_MAJOR;
  }

  @Override
  public int getMinorVersion() {
    return VERSION_MINOR;
  }

  @Override
  public boolean jdbcCompliant() {
    return false;
  }

  @Override
  public Logger getParentLogger() {
    return Logger.getLogger("io.turso.jdbc");
  }
}

package io.turso.jdbc;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;
import javax.sql.DataSource;

/** Simple DataSource; pooling is delegated to HikariCP or the container. */
public final class TursoDataSource implements DataSource {
  private final TursoDriver driver = new TursoDriver();
  private final String url;
  private final Properties properties;
  private PrintWriter logWriter;
  private int loginTimeout;

  public TursoDataSource(String url) {
    this(url, new Properties());
  }

  public TursoDataSource(String url, Properties properties) {
    if (url == null || !driver.acceptsURL(url)) {
      throw new IllegalArgumentException("Not a Turso JDBC URL: " + url);
    }
    this.url = url;
    this.properties = new Properties();
    if (properties != null) {
      this.properties.putAll(properties);
    }
  }

  public String getUrl() {
    return url;
  }

  @Override
  public Connection getConnection() throws SQLException {
    return driver.connect(url, properties);
  }

  @Override
  public Connection getConnection(String username, String password) throws SQLException {
    Properties copy = new Properties();
    copy.putAll(properties);
    if (username != null) {
      copy.setProperty("user", username);
    }
    if (password != null) {
      copy.setProperty("password", password);
    }
    return driver.connect(url, copy);
  }

  @Override
  public PrintWriter getLogWriter() {
    return logWriter;
  }

  @Override
  public void setLogWriter(PrintWriter out) {
    this.logWriter = out;
  }

  @Override
  public void setLoginTimeout(int seconds) {
    this.loginTimeout = seconds;
  }

  @Override
  public int getLoginTimeout() {
    return loginTimeout;
  }

  @Override
  public Logger getParentLogger() {
    return Logger.getLogger("io.turso.jdbc");
  }

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    if (iface.isInstance(this)) {
      return iface.cast(this);
    }
    throw new SQLFeatureNotSupportedException("Not a wrapper for " + iface.getName());
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) {
    return iface.isInstance(this);
  }
}

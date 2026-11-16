package io.turso.jdbc;

import io.turso.jdbc.base.AbstractStatement;
import io.turso.jdbc.protocol.Stmt;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Forward-only JDBC statement. */
public class TursoStatement extends AbstractStatement {
  protected final TursoConnection connection;
  protected final List<Stmt> batch = new ArrayList<>();
  protected final List<Stmt> preparedBatch = new ArrayList<>();
  protected TursoResultSet resultSet;
  protected long updateCount = -1;
  protected TursoResultSet generatedKeys;
  protected int maxRows;
  protected int queryTimeoutSeconds;
  protected boolean closeOnCompletion;
  protected boolean poolable;
  protected int fetchSize;
  protected boolean closed;
  protected String lastSql;

  TursoStatement(TursoConnection connection) {
    this.connection = connection;
  }

  // ---------------------------------------------------------------- execution

  @Override
  public boolean execute(String sql) throws SQLException {
    checkOpen();
    closeCurrent();
    checkOpen();
    lastSql = sql;
    TursoConnection.Outcome outcome = connection.execute(this, Stmt.of(sql), maxRows, timeout());
    if (outcome.resultSet() != null) {
      resultSet = outcome.resultSet();
      updateCount = -1;
      return true;
    }
    updateCount = outcome.updateCount();
    setGeneratedKeys(outcome.lastInsertRowid());
    return false;
  }

  @Override
  public ResultSet executeQuery(String sql) throws SQLException {
    checkOpen();
    closeCurrent();
    checkOpen();
    lastSql = sql;
    TursoConnection.Outcome outcome = connection.execute(this, Stmt.of(sql), maxRows, timeout());
    if (outcome.resultSet() == null) {
      throw new SQLException("Statement did not return a result set", "HY000");
    }
    resultSet = outcome.resultSet();
    updateCount = -1;
    return resultSet;
  }

  @Override
  public int executeUpdate(String sql) throws SQLException {
    long count = executeLargeUpdate(sql);
    if (count > Integer.MAX_VALUE) {
      throw new SQLException("Update count exceeds Integer.MAX_VALUE", "22003");
    }
    return (int) count;
  }

  @Override
  public long executeLargeUpdate(String sql) throws SQLException {
    checkOpen();
    closeCurrent();
    checkOpen();
    lastSql = sql;
    TursoConnection.Outcome outcome = connection.update(this, Stmt.of(sql), timeout());
    updateCount = outcome.updateCount();
    setGeneratedKeys(outcome.lastInsertRowid());
    return updateCount;
  }

  @Override
  public long[] executeLargeBatch() throws SQLException {
    List<Stmt> statements = batchStatements();
    if (statements.isEmpty()) {
      return new long[0];
    }
    TursoConnection.BatchOutcome outcome = connection.executeBatch(statements, timeout());
    batch.clear();
    preparedBatch().clear();
    setGeneratedKeys(outcome.lastInsertRowid());
    return outcome.counts();
  }

  @Override
  public int[] executeBatch() throws SQLException {
    long[] counts = executeLargeBatch();
    int[] result = new int[counts.length];
    for (int i = 0; i < counts.length; i++) {
      result[i] = counts[i] > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) counts[i];
    }
    return result;
  }

  protected List<Stmt> batchStatements() {
    List<Stmt> statements = new ArrayList<>(batch);
    statements.addAll(preparedBatch());
    return statements;
  }

  protected List<Stmt> preparedBatch() {
    return preparedBatch;
  }

  @Override
  public void addBatch(String sql) throws SQLException {
    checkOpen();
    if (Sql.isTransactionControl(sql)) {
      throw new SQLException("Transaction control statements are not allowed in a batch");
    }
    batch.add(Stmt.of(sql));
  }

  @Override
  public void clearBatch() throws SQLException {
    checkOpen();
    batch.clear();
    preparedBatch().clear();
  }

  // ---------------------------------------------------------------- results

  @Override
  public ResultSet getResultSet() throws SQLException {
    checkOpen();
    return resultSet;
  }

  @Override
  public int getUpdateCount() throws SQLException {
    checkOpen();
    return resultSet != null ? -1 : (updateCount > Integer.MAX_VALUE ? Integer.MAX_VALUE
        : (int) updateCount);
  }

  @Override
  public long getLargeUpdateCount() throws SQLException {
    checkOpen();
    return resultSet != null ? -1 : updateCount;
  }

  @Override
  public boolean getMoreResults() throws SQLException {
    return getMoreResults(Statement.CLOSE_CURRENT_RESULT);
  }

  @Override
  public boolean getMoreResults(int current) throws SQLException {
    checkOpen();
    if (resultSet != null) {
      resultSet.close();
      resultSet = null;
    }
    updateCount = -1;
    return false;
  }

  @Override
  public ResultSet getGeneratedKeys() throws SQLException {
    checkOpen();
    return generatedKeys != null ? generatedKeys : TursoResultSet.empty(connection, this);
  }

  protected void setGeneratedKeys(Long rowid) {
    if (rowid == null) {
      generatedKeys = null;
      return;
    }
    List<Column> columns = List.of(new Column("last_insert_rowid", "INTEGER", java.sql.Types.BIGINT));
    generatedKeys = TursoResultSet.of(connection, this, columns, List.<Object[]>of(new Object[] {rowid}));
  }

  void resultSetClosed(TursoResultSet rs) {
    if (resultSet == rs) {
      resultSet = null;
    }
    if (closeOnCompletion && !closed) {
      try {
        close();
      } catch (SQLException ignored) {
        // best effort
      }
    }
  }

  // ---------------------------------------------------------------- settings

  @Override
  public void setQueryTimeout(int seconds) throws SQLException {
    checkOpen();
    if (seconds < 0) {
      throw new SQLException("Query timeout cannot be negative");
    }
    queryTimeoutSeconds = seconds;
  }

  @Override
  public int getQueryTimeout() throws SQLException {
    checkOpen();
    return queryTimeoutSeconds;
  }

  @Override
  public void setMaxRows(int max) throws SQLException {
    checkOpen();
    if (max < 0) {
      throw new SQLException("Max rows cannot be negative");
    }
    maxRows = max;
  }

  @Override
  public int getMaxRows() throws SQLException {
    checkOpen();
    return maxRows;
  }

  @Override
  public void setMaxFieldSize(int max) throws SQLException {
    checkOpen();
  }

  @Override
  public int getMaxFieldSize() throws SQLException {
    checkOpen();
    return 0;
  }

  @Override
  public void setFetchSize(int rows) throws SQLException {
    checkOpen();
    if (rows < 0) {
      throw new SQLException("Fetch size cannot be negative");
    }
    fetchSize = rows;
  }

  @Override
  public int getFetchSize() throws SQLException {
    checkOpen();
    return fetchSize;
  }

  @Override
  public void setFetchDirection(int direction) throws SQLException {
    checkOpen();
    if (direction != ResultSet.FETCH_FORWARD) {
      throw new SQLFeatureNotSupportedException("Only FETCH_FORWARD is supported");
    }
  }

  @Override
  public int getFetchDirection() throws SQLException {
    checkOpen();
    return ResultSet.FETCH_FORWARD;
  }

  @Override
  public void setEscapeProcessing(boolean enable) throws SQLException {
    checkOpen();
  }

  @Override
  public void closeOnCompletion() throws SQLException {
    checkOpen();
    closeOnCompletion = true;
  }

  @Override
  public boolean isCloseOnCompletion() throws SQLException {
    checkOpen();
    return closeOnCompletion;
  }

  @Override
  public void setPoolable(boolean poolable) throws SQLException {
    checkOpen();
    this.poolable = poolable;
  }

  @Override
  public boolean isPoolable() throws SQLException {
    checkOpen();
    return poolable;
  }

  @Override
  public int getResultSetType() {
    return ResultSet.TYPE_FORWARD_ONLY;
  }

  @Override
  public int getResultSetConcurrency() {
    return ResultSet.CONCUR_READ_ONLY;
  }

  @Override
  public int getResultSetHoldability() {
    return ResultSet.HOLD_CURSORS_OVER_COMMIT;
  }

  // ---------------------------------------------------------------- lifecycle

  @Override
  public void cancel() throws SQLException {
    if (resultSet != null) {
      resultSet.close();
      resultSet = null;
    }
  }

  @Override
  public void close() throws SQLException {
    if (closed) {
      return;
    }
    closed = true;
    if (resultSet != null) {
      resultSet.close();
      resultSet = null;
    }
    if (generatedKeys != null) {
      generatedKeys.close();
      generatedKeys = null;
    }
    batch.clear();
    connection.unregister(this);
  }

  @Override
  public boolean isClosed() {
    return closed;
  }

  @Override
  public java.sql.Connection getConnection() {
    return connection;
  }

  @Override
  public SQLWarning getWarnings() {
    return null;
  }

  @Override
  public void clearWarnings() {
    // no-op
  }

  @Override
  public void setCursorName(String name) throws SQLException {
    throw new SQLFeatureNotSupportedException("Named cursors are not supported");
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

  protected Duration timeout() {
    return queryTimeoutSeconds > 0 ? Duration.ofSeconds(queryTimeoutSeconds) : null;
  }

  protected void checkOpen() throws SQLException {
    if (closed) {
      throw new SQLException("Statement is closed", "24000");
    }
    connection.checkOpen();
  }

  protected void closeCurrent() throws SQLException {
    if (resultSet != null) {
      resultSet.close();
      resultSet = null;
    }
    if (generatedKeys != null) {
      generatedKeys.close();
      generatedKeys = null;
    }
    updateCount = -1;
  }
}

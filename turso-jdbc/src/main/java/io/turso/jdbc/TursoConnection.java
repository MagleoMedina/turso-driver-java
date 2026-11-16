package io.turso.jdbc;

import io.turso.jdbc.base.AbstractConnection;
import io.turso.jdbc.protocol.Condition;
import io.turso.jdbc.protocol.CursorStream;
import io.turso.jdbc.protocol.Json;
import io.turso.jdbc.protocol.Session;
import io.turso.jdbc.protocol.Step;
import io.turso.jdbc.protocol.Stmt;
import io.turso.jdbc.protocol.TursoErrors;
import io.turso.jdbc.protocol.Values;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** JDBC connection backed by one Turso HTTP stream. */
public final class TursoConnection extends AbstractConnection {
  record Outcome(TursoResultSet resultSet, long updateCount, Long lastInsertRowid) {}

  record BatchOutcome(long[] counts, Long lastInsertRowid) {}

  private final TursoConfig config;
  private final Session session;
  private final String jdbcUrl;
  private final String transactionMode;
  private final Duration requestTimeout;
  private final List<TursoStatement> statements = new ArrayList<>();

  private boolean autoCommit = true;
  private boolean inTransaction;
  private boolean closed;
  private boolean readOnly;
  private int isolation = Connection.TRANSACTION_SERIALIZABLE;
  private String schema = "main";
  private int savepointCounter;
  private int networkTimeoutMs;
  private TursoDatabaseMetaData metaData;

  TursoConnection(TursoConfig config) {
    this.config = config;
    this.jdbcUrl = config.jdbcUrl();
    this.transactionMode = config.transactionMode();
    this.requestTimeout = config.requestTimeout();
    this.readOnly = config.readOnly();
    this.session = new Session(config.url(), config.token(), config.encryptionKey(),
        config.connectTimeout(), config.requestTimeout());
  }

  // ---------------------------------------------------------------- statements

  Outcome execute(TursoStatement statement, Stmt stmt, int maxRows, Duration timeout)
      throws SQLException {
    checkOpen();
    boolean begin = needsBegin(stmt);
    List<Step> steps = new ArrayList<>(2);
    if (begin) {
      steps.add(Step.of(beginStmt()));
    }
    int target = steps.size();
    steps.add(begin ? Step.of(Condition.ok(0), stmt) : Step.of(stmt));

    CursorStream stream;
    try {
      stream = session.cursor(steps, autoCommit && !inTransaction, timeout);
    } catch (SQLException e) {
      if (!session.hasStream()) {
        inTransaction = false;
      }
      throw e;
    }
    if (begin) {
      inTransaction = true;
    }
    TursoResultSet rs = new TursoResultSet(this, statement, stream, begin ? 0 : -1, target,
        stmt.sql(), maxRows);
    try {
      rs.ensureColumns();
      if (rs.columnCount() == 0) {
        rs.drain();
        long count = rs.updateCount();
        Long rowid = rs.lastInsertRowid();
        rs.close();
        afterStatement(stmt.sql());
        ensureTransactionAlive();
        return new Outcome(null, Math.max(count, 0), rowid);
      }
      return new Outcome(rs, -1, null);
    } catch (SQLException e) {
      if (rs.columnCount() == 0) {
        rs.close();
      }
      if (!session.hasStream()) {
        inTransaction = false;
      }
      throw e;
    }
  }

  Outcome update(TursoStatement statement, Stmt stmt, Duration timeout) throws SQLException {
    checkOpen();
    boolean begin = needsBegin(stmt);
    boolean allowReconnect = autoCommit && !inTransaction;
    Map<String, Object> response;
    try {
      response = session.pipeline(w -> {
        if (begin) {
          writeExecute(w, beginStmt());
        }
        writeExecute(w, stmt.withWantRows(false));
      }, allowReconnect, timeout);
      List<Object> results = Json.array(response.get("results"));
      if (begin) {
        ok(results.get(0), "BEGIN");
        inTransaction = true;
      }
      Map<String, Object> userResult = ok(results.get(begin ? 1 : 0), stmt.sql());
      Map<String, Object> result = map(userResult.get("result"));
      long count = longValue(result.get("affected_row_count"));
      Long rowid = rowid(result.get("last_insert_rowid"));
      afterStatement(stmt.sql());
      ensureTransactionAlive();
      return new Outcome(null, count, rowid);
    } catch (SQLException e) {
      if (!session.hasStream()) {
        inTransaction = false;
      }
      throw e;
    }
  }

  BatchOutcome executeBatch(List<Stmt> stmts, Duration timeout) throws SQLException {
    checkOpen();
    if (stmts.isEmpty()) {
      return new BatchOutcome(new long[0], null);
    }
    boolean wrap = autoCommit;
    List<Step> steps = new ArrayList<>(stmts.size() + 3);
    if (wrap) {
      steps.add(Step.of(beginStmt()));
    }
    int first = steps.size();
    for (int i = 0; i < stmts.size(); i++) {
      Stmt stmt = stmts.get(i).withWantRows(false);
      if (wrap) {
        steps.add(Step.of(Condition.ok(i == 0 ? 0 : first + i - 1), stmt));
      } else {
        steps.add(i == 0 ? Step.of(stmt) : Step.of(Condition.ok(first + i - 1), stmt));
      }
    }
    int commitIdx = -1;
    if (wrap) {
      commitIdx = steps.size();
      steps.add(Step.of(Condition.ok(first + stmts.size() - 1),
          Stmt.of("COMMIT", List.of(), false)));
      steps.add(Step.of(
          Condition.and(Condition.ok(0), Condition.not(Condition.ok(commitIdx))),
          Stmt.of("ROLLBACK", List.of(), false)));
    }

    Map<String, Object> response;
    try {
      response = session.pipeline(w -> {
        w.beginObject().name("type").value("batch");
        w.name("batch").beginObject().name("steps").beginArray();
        for (Step step : steps) {
          step.write(w);
        }
        w.endArray().endObject();
        w.endObject();
      }, autoCommit && !inTransaction, timeout);
      Map<String, Object> batchResponse = ok(Json.array(response.get("results")).get(0), "BATCH");
      Map<String, Object> result = map(batchResponse.get("result"));
      List<Object> stepResults = Json.array(result.get("step_results"));
      List<Object> stepErrors = Json.array(result.get("step_errors"));

      long[] counts = new long[stmts.size()];
      SQLException firstError = null;
      Long lastRowid = null;
      for (int i = 0; i < stmts.size(); i++) {
        int idx = first + i;
        Object error = idx < stepErrors.size() ? stepErrors.get(idx) : null;
        Object stepResult = idx < stepResults.size() ? stepResults.get(idx) : null;
        if (error != null) {
          counts[i] = Statement.EXECUTE_FAILED;
          if (firstError == null) {
            firstError = TursoErrors.protocol(error, stmts.get(i).sql());
          }
        } else if (stepResult != null) {
          Map<String, Object> r = map(stepResult);
          counts[i] = longValue(r.get("affected_row_count"));
          Long rid = rowid(r.get("last_insert_rowid"));
          if (rid != null) {
            lastRowid = rid;
          }
        } else {
          counts[i] = Statement.EXECUTE_FAILED;
        }
      }
      if (wrap) {
        inTransaction = false;
        Object commitError = commitIdx < stepErrors.size() ? stepErrors.get(commitIdx) : null;
        if (commitError != null && firstError == null) {
          firstError = TursoErrors.protocol(commitError, "COMMIT");
        }
      } else {
        ensureTransactionAlive();
      }
      if (firstError != null) {
        throw TursoErrors.batch(firstError.getMessage(), firstError.getSQLState(), firstError, counts);
      }
      return new BatchOutcome(counts, lastRowid);
    } catch (SQLException e) {
      if (!session.hasStream()) {
        inTransaction = false;
      }
      throw e;
    }
  }

  Map<String, Object> describe(String sql, Duration timeout) throws SQLException {
    checkOpen();
    try {
      Map<String, Object> response = session.pipeline(
          w -> w.beginObject().name("type").value("describe").name("sql").value(sql).endObject(),
          autoCommit && !inTransaction, timeout);
      Map<String, Object> userResult = ok(Json.array(response.get("results")).get(0), sql);
      return map(userResult.get("result"));
    } catch (SQLException e) {
      if (!session.hasStream()) {
        inTransaction = false;
      }
      throw e;
    }
  }

  // ---------------------------------------------------------------- transactions

  public void setAutoCommit(boolean enabled) throws SQLException {
    checkOpen();
    if (enabled == autoCommit) {
      return;
    }
    if (enabled && inTransaction) {
      commit();
    }
    autoCommit = enabled;
  }

  public boolean getAutoCommit() throws SQLException {
    checkOpen();
    return autoCommit;
  }

  public void commit() throws SQLException {
    checkOpen();
    if (autoCommit) {
      throw new SQLException("Cannot commit when auto-commit is enabled");
    }
    if (!inTransaction) {
      return;
    }
    if (!session.hasStream()) {
      inTransaction = false;
      throw TursoErrors.streamExpired("Cannot commit: the transaction was lost");
    }
    try {
      Map<String, Object> response = session.pipeline(
          w -> writeExecute(w, Stmt.of("COMMIT", List.of(), false)), false, null);
      ok(Json.array(response.get("results")).get(0), "COMMIT");
      inTransaction = false;
    } catch (SQLException e) {
      if (!session.hasStream()) {
        inTransaction = false;
      }
      throw e;
    }
  }

  public void rollback() throws SQLException {
    checkOpen();
    if (autoCommit) {
      throw new SQLException("Cannot rollback when auto-commit is enabled");
    }
    inTransaction = false;
    if (!session.hasStream()) {
      return;
    }
    try {
      session.pipeline(w -> writeExecute(w, Stmt.of("ROLLBACK", List.of(), false)), false, null);
    } catch (SQLException ignored) {
      // The stream is gone: the transaction was already rolled back by the server.
    }
  }

  public Savepoint setSavepoint() throws SQLException {
    return createSavepoint(null);
  }

  public Savepoint setSavepoint(String name) throws SQLException {
    return createSavepoint(name);
  }

  private Savepoint createSavepoint(String name) throws SQLException {
    checkOpen();
    if (autoCommit) {
      throw new SQLException("Savepoints require auto-commit to be disabled");
    }
    if (!inTransaction) {
      beginTransaction();
    }
    int id = ++savepointCounter;
    String savepointName = name == null ? "turso_sp_" + id : name;
    control("SAVEPOINT " + Sql.quoteIdentifier(savepointName));
    return new TursoSavepoint(id, name == null ? savepointName : name);
  }

  public void rollback(Savepoint savepoint) throws SQLException {
    checkOpen();
    TursoSavepoint sp = validSavepoint(savepoint);
    control("ROLLBACK TO SAVEPOINT " + Sql.quoteIdentifier(sp.name()));
  }

  public void releaseSavepoint(Savepoint savepoint) throws SQLException {
    checkOpen();
    TursoSavepoint sp = validSavepoint(savepoint);
    control("RELEASE SAVEPOINT " + Sql.quoteIdentifier(sp.name()));
    sp.invalidate();
  }

  private TursoSavepoint validSavepoint(Savepoint savepoint) throws SQLException {
    if (!(savepoint instanceof TursoSavepoint sp) || !sp.isValid()) {
      throw new SQLException("Invalid or released savepoint");
    }
    if (!inTransaction) {
      throw new SQLException("No transaction is active");
    }
    return sp;
  }

  private void beginTransaction() throws SQLException {
    Map<String, Object> response = session.pipeline(w -> writeExecute(w, beginStmt()), false, null);
    ok(Json.array(response.get("results")).get(0), "BEGIN");
    inTransaction = true;
  }

  private void control(String sql) throws SQLException {
    Map<String, Object> response = session.pipeline(
        w -> writeExecute(w, Stmt.of(sql, List.of(), false)), false, null);
    ok(Json.array(response.get("results")).get(0), sql);
  }

  // ---------------------------------------------------------------- lifecycle

  public Statement createStatement() throws SQLException {
    return createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
  }

  public Statement createStatement(int type, int concurrency) throws SQLException {
    return createStatement(type, concurrency, ResultSet.HOLD_CURSORS_OVER_COMMIT);
  }

  public Statement createStatement(int type, int concurrency, int holdability) throws SQLException {
    checkOpen();
    checkResultSet(type, concurrency);
    TursoStatement statement = new TursoStatement(this);
    statements.add(statement);
    return statement;
  }

  public PreparedStatement prepareStatement(String sql) throws SQLException {
    return prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
  }

  public PreparedStatement prepareStatement(String sql, int type, int concurrency)
      throws SQLException {
    return prepareStatement(sql, type, concurrency, ResultSet.HOLD_CURSORS_OVER_COMMIT);
  }

  public PreparedStatement prepareStatement(String sql, int type, int concurrency, int holdability)
      throws SQLException {
    checkOpen();
    checkResultSet(type, concurrency);
    TursoPreparedStatement statement = new TursoPreparedStatement(this, sql);
    statements.add(statement);
    return statement;
  }

  public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
    return prepareStatement(sql);
  }

  public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
    return prepareStatement(sql);
  }

  public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
    return prepareStatement(sql);
  }

  public String nativeSQL(String sql) {
    return sql;
  }

  public void close() throws SQLException {
    if (closed) {
      return;
    }
    closed = true;
    for (TursoStatement statement : new ArrayList<>(statements)) {
      try {
        statement.close();
      } catch (SQLException ignored) {
        // keep closing the rest
      }
    }
    statements.clear();
    session.close();
  }

  public boolean isClosed() {
    return closed;
  }

  public boolean isValid(int timeout) {
    if (closed) {
      return false;
    }
    try {
      Duration duration = Duration.ofSeconds(Math.max(1, timeout));
      session.pipeline(w -> writeExecute(w, Stmt.of("SELECT 1")), autoCommit && !inTransaction,
          duration);
      return true;
    } catch (SQLException e) {
      if (!session.hasStream()) {
        inTransaction = false;
      }
      return false;
    }
  }

  public void abort(java.util.concurrent.Executor executor) throws SQLException {
    close();
  }

  public DatabaseMetaData getMetaData() throws SQLException {
    checkOpen();
    if (metaData == null) {
      metaData = new TursoDatabaseMetaData(this);
    }
    return metaData;
  }

  public void setReadOnly(boolean readOnly) {
    this.readOnly = readOnly;
  }

  public boolean isReadOnly() {
    return readOnly;
  }

  public void setCatalog(String catalog) {
    // SQLite has no catalogs.
  }

  public String getCatalog() {
    return null;
  }

  public void setSchema(String schema) {
    if (schema != null && !schema.isBlank()) {
      this.schema = schema;
    }
  }

  public String getSchema() {
    return schema;
  }

  public int getTransactionIsolation() {
    return isolation;
  }

  public void setTransactionIsolation(int level) throws SQLException {
    if (level == Connection.TRANSACTION_SERIALIZABLE || level == Connection.TRANSACTION_READ_COMMITTED
        || level == Connection.TRANSACTION_NONE) {
      this.isolation = level;
      return;
    }
    throw new SQLFeatureNotSupportedException("Unsupported transaction isolation: " + level);
  }

  public java.util.Map<String, Class<?>> getTypeMap() {
    return new HashMap<>();
  }

  public void setTypeMap(java.util.Map<String, Class<?>> map) {
    // no-op
  }

  public int getHoldability() {
    return ResultSet.HOLD_CURSORS_OVER_COMMIT;
  }

  public void setHoldability(int holdability) throws SQLException {
    if (holdability != ResultSet.HOLD_CURSORS_OVER_COMMIT) {
      throw new SQLFeatureNotSupportedException("Only HOLD_CURSORS_OVER_COMMIT is supported");
    }
  }

  public java.sql.SQLWarning getWarnings() {
    return null;
  }

  public void clearWarnings() {
    // no-op
  }

  public Properties getClientInfo() {
    return new Properties();
  }

  public String getClientInfo(String name) {
    return null;
  }

  public void setClientInfo(String name, String value) {
    // no-op
  }

  public void setClientInfo(Properties properties) {
    // no-op
  }

  public int getNetworkTimeout() {
    return networkTimeoutMs;
  }

  public void setNetworkTimeout(java.util.concurrent.Executor executor, int milliseconds) {
    this.networkTimeoutMs = milliseconds;
  }

  public <T> T unwrap(Class<T> iface) throws SQLException {
    if (iface.isInstance(this)) {
      return iface.cast(this);
    }
    throw new SQLFeatureNotSupportedException("Not a wrapper for " + iface.getName());
  }

  public boolean isWrapperFor(Class<?> iface) {
    return iface.isInstance(this);
  }

  // ---------------------------------------------------------------- internal API

  Session session() {
    return session;
  }

  String jdbcUrl() {
    return jdbcUrl;
  }

  Duration requestTimeout() {
    return requestTimeout;
  }

  String transactionMode() {
    return transactionMode;
  }

  TursoConfig config() {
    return config;
  }

  void unregister(TursoStatement statement) {
    statements.remove(statement);
  }

  void checkOpen() throws SQLException {
    if (closed) {
      throw new SQLException("Connection is closed", "08003");
    }
  }

  private boolean needsBegin(Stmt stmt) {
    if (autoCommit || inTransaction) {
      return false;
    }
    String keyword = Sql.firstKeyword(stmt.sql());
    return keyword == null || !Sql.isTransactionControl(stmt.sql());
  }

  private Stmt beginStmt() {
    return Stmt.of("BEGIN " + transactionMode, List.of(), false);
  }

  private void afterStatement(String sql) {
    String keyword = Sql.firstKeyword(sql);
    if (keyword == null) {
      return;
    }
    switch (keyword) {
      case "BEGIN" -> inTransaction = true;
      case "COMMIT", "END", "ROLLBACK" -> inTransaction = false;
      default -> {
        // no change
      }
    }
  }

  void ensureTransactionAlive() throws SQLException {
    if (inTransaction && !session.hasStream()) {
      inTransaction = false;
      throw TursoErrors.streamExpired("The interactive transaction was lost");
    }
  }

  /** Called by the cursor result set when the BEGIN step completed successfully. */
  void cursorBeginOk() {
    if (!autoCommit) {
      inTransaction = true;
    }
  }

  /** Called by the cursor result set when the user step completed. */
  void cursorStepEnd(int step, int beginStep, String sql, long affected, Long rowid)
      throws SQLException {
    if (step == beginStep) {
      cursorBeginOk();
      return;
    }
    afterStatement(sql);
    ensureTransactionAlive();
  }

  /** Called by the cursor result set when a step failed. */
  void cursorStepError(int step, int beginStep) {
    if (step == beginStep) {
      inTransaction = false;
    }
    if (!session.hasStream()) {
      inTransaction = false;
    }
  }

  String dbVersion() throws SQLException {
    Map<String, Object> response = session.pipeline(
        w -> writeExecute(w, Stmt.of("SELECT sqlite_version()")),
        autoCommit && !inTransaction, requestTimeout);
    Map<String, Object> userResult = ok(Json.array(response.get("results")).get(0),
        "SELECT sqlite_version()");
    Map<String, Object> result = map(userResult.get("result"));
    List<Object> rows = Json.array(result.get("rows"));
    if (rows.isEmpty()) {
      return null;
    }
    List<Object> row = Json.array(rows.get(0));
    return row.isEmpty() ? null : Convert.str(Values.decode(row.get(0)));
  }

  private void checkResultSet(int type, int concurrency) throws SQLException {
    if (type != ResultSet.TYPE_FORWARD_ONLY) {
      throw new SQLFeatureNotSupportedException("Only forward-only result sets are supported");
    }
    if (concurrency != ResultSet.CONCUR_READ_ONLY) {
      throw new SQLFeatureNotSupportedException("Only read-only result sets are supported");
    }
  }

  static void writeExecute(Json.Writer w, Stmt stmt) {
    w.beginObject().name("type").value("execute").name("stmt");
    stmt.write(w);
    w.endObject();
  }

  static Map<String, Object> ok(Object raw, String sql) throws SQLException {
    if (raw == null) {
      throw TursoErrors.truncated("Missing pipeline result");
    }
    Map<String, Object> result = map(raw);
    if ("error".equals(result.get("type"))) {
      throw TursoErrors.protocol(result.get("error"), sql);
    }
    Object response = result.get("response");
    return response == null ? Map.of() : map(response);
  }

  static Map<String, Object> map(Object value) {
    if (value instanceof Map<?, ?> map) {
      return Json.object(map);
    }
    return Map.of();
  }

  static long longValue(Object value) {
    return value instanceof Number n ? n.longValue() : 0L;
  }

  static Long rowid(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number n) {
      return n.longValue();
    }
    String s = value.toString().trim();
    if (s.isEmpty()) {
      return null;
    }
    try {
      return Long.parseLong(s);
    } catch (NumberFormatException e) {
      return null;
    }
  }
}

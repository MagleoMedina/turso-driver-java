package io.turso.jdbc;

import io.turso.jdbc.base.AbstractResultSet;
import io.turso.jdbc.protocol.CursorStream;
import io.turso.jdbc.protocol.Json;
import io.turso.jdbc.protocol.TursoErrors;
import io.turso.jdbc.protocol.Values;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URL;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Forward-only, read-only result set. Cursor results are streamed row by row. */
public final class TursoResultSet extends AbstractResultSet {
  private final TursoConnection connection;
  private final TursoStatement statement;
  private final CursorStream stream;
  private final int beginStep;
  private final int targetStep;
  private final String sql;
  private final int maxRows;
  private final List<Object[]> staticRows;

  private List<Column> columns;
  private TursoResultSetMetaData metaData;
  private int staticIndex = -1;
  private Object[] current;
  private int rowNumber;
  private int currentStep = -1;
  private boolean lastWasNull;
  private boolean closed;
  private boolean done;
  private boolean columnsReady;
  private long updateCount = -1;
  private Long lastInsertRowid;

  TursoResultSet(TursoConnection connection, TursoStatement statement, CursorStream stream,
      int beginStep, int targetStep, String sql, int maxRows) {
    this.connection = connection;
    this.statement = statement;
    this.stream = stream;
    this.beginStep = beginStep;
    this.targetStep = targetStep;
    this.sql = sql;
    this.maxRows = maxRows;
    this.staticRows = null;
  }

  private TursoResultSet(TursoConnection connection, TursoStatement statement, List<Column> columns,
      List<Object[]> rows) {
    this.connection = connection;
    this.statement = statement;
    this.stream = null;
    this.beginStep = -1;
    this.targetStep = -1;
    this.sql = "";
    this.maxRows = 0;
    this.columns = columns;
    this.staticRows = rows;
    this.columnsReady = true;
  }

  static TursoResultSet of(TursoConnection connection, TursoStatement statement, List<Column> columns,
      List<Object[]> rows) {
    return new TursoResultSet(connection, statement, columns, rows);
  }

  static TursoResultSet empty(TursoConnection connection, TursoStatement statement) {
    return new TursoResultSet(connection, statement, List.of(), List.of());
  }

  // ---------------------------------------------------------------- cursor plumbing

  void ensureColumns() throws SQLException {
    checkOpen();
    if (columnsReady) {
      return;
    }
    if (stream == null) {
      columns = List.of();
      columnsReady = true;
      return;
    }
    while (!columnsReady) {
      Map<String, Object> entry = stream.next();
      if (entry == null) {
        throw TursoErrors.truncated("Cursor ended before the statement started");
      }
      switch (Json.string(entry.get("type"))) {
        case "step_begin" -> {
          currentStep = intOf(entry.get("step"));
          if (currentStep == targetStep) {
            columns = parseColumns(entry.get("cols"));
            columnsReady = true;
          }
        }
        case "step_end" -> {
          if (currentStep == beginStep) {
            connection.cursorBeginOk();
          }
        }
        case "step_error" -> {
          int step = intOf(entry.get("step"));
          connection.cursorStepError(step, beginStep);
          throw TursoErrors.protocol(entry.get("error"), sql);
        }
        case "error" -> throw TursoErrors.protocol(entry.get("error"), sql);
        default -> {
          // rows of a previous step are not expected here
        }
      }
    }
  }

  int columnCount() {
    return columns == null ? 0 : columns.size();
  }

  List<Column> columns() {
    return columns == null ? List.of() : columns;
  }

  long updateCount() {
    return updateCount < 0 ? 0 : updateCount;
  }

  Long lastInsertRowid() {
    return lastInsertRowid;
  }

  void drain() throws SQLException {
    while (next()) {
      // consume
    }
  }

  private static List<Column> parseColumns(Object raw) {
    List<Object> list = Json.array(raw);
    List<Column> result = new ArrayList<>(list.size());
    for (Object item : list) {
      Map<String, Object> col = Json.object(item);
      result.add(Column.of(Json.string(col.get("name")), Json.string(col.get("decltype"))));
    }
    return result;
  }

  private static int intOf(Object value) {
    return value instanceof Number n ? n.intValue() : -1;
  }

  private static long longOf(Object value) {
    return value instanceof Number n ? n.longValue() : 0L;
  }

  private static Long rowidOf(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number n) {
      return n.longValue();
    }
    try {
      return Long.parseLong(value.toString());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  // ---------------------------------------------------------------- rows

  @Override
  public boolean next() throws SQLException {
    checkOpen();
    if (!columnsReady) {
      ensureColumns();
    }
    if (staticRows != null) {
      if (staticIndex + 1 >= staticRows.size()) {
        done = true;
        current = null;
        return false;
      }
      current = staticRows.get(++staticIndex);
      rowNumber++;
      lastWasNull = false;
      return true;
    }
    if (done) {
      return false;
    }
    while (true) {
      Map<String, Object> entry;
      try {
        entry = stream.next();
      } catch (SQLException e) {
        closeQuietly();
        throw e;
      }
      if (entry == null) {
        done = true;
        current = null;
        throw TursoErrors.truncated("Cursor ended before the statement completed");
      }
      switch (Json.string(entry.get("type"))) {
        case "row" -> {
          if (currentStep != targetStep) {
            continue;
          }
          List<Object> values = Json.array(entry.get("row"));
          Object[] row = new Object[values.size()];
          for (int i = 0; i < values.size(); i++) {
            row[i] = Values.decode(values.get(i));
          }
          current = row;
          rowNumber++;
          lastWasNull = false;
          if (maxRows > 0 && rowNumber > maxRows) {
            done = true;
            current = null;
            closeQuietly();
            return false;
          }
          return true;
        }
        case "step_end" -> {
          if (currentStep == targetStep) {
            updateCount = longOf(entry.get("affected_row_count"));
            lastInsertRowid = rowidOf(entry.get("last_insert_rowid"));
            connection.cursorStepEnd(targetStep, beginStep, sql, updateCount, lastInsertRowid);
            done = true;
            current = null;
            closeQuietly();
            return false;
          }
          if (currentStep == beginStep) {
            connection.cursorBeginOk();
          }
        }
        case "step_error" -> {
          int step = intOf(entry.get("step"));
          connection.cursorStepError(step, beginStep);
          closeQuietly();
          throw TursoErrors.protocol(entry.get("error"), sql);
        }
        case "error" -> {
          closeQuietly();
          throw TursoErrors.protocol(entry.get("error"), sql);
        }
        case "replication_index" -> {
          done = true;
          current = null;
          closeQuietly();
          return false;
        }
        default -> {
          // Ignore unknown entry types for forward compatibility.
        }
      }
    }
  }

  // ---------------------------------------------------------------- metadata

  @Override
  public ResultSetMetaData getMetaData() throws SQLException {
    checkOpen();
    ensureColumns();
    if (metaData == null) {
      metaData = new TursoResultSetMetaData(columns());
    }
    return metaData;
  }

  @Override
  public int findColumn(String columnLabel) throws SQLException {
    checkOpen();
    ensureColumns();
    for (int i = 0; i < columns().size(); i++) {
      if (columns().get(i).name().equalsIgnoreCase(columnLabel)) {
        return i + 1;
      }
    }
    throw new SQLException("Column not found: " + columnLabel, "S0022");
  }

  // ---------------------------------------------------------------- value access

  private Object value(int columnIndex) throws SQLException {
    checkOpen();
    if (current == null) {
      throw new SQLException("No current row", "24000");
    }
    if (columnIndex < 1 || columnIndex > current.length) {
      throw new SQLException("Column index out of range: " + columnIndex, "S0022");
    }
    Object value = current[columnIndex - 1];
    lastWasNull = value == null;
    return value;
  }

  private Object value(String columnLabel) throws SQLException {
    return value(findColumn(columnLabel));
  }

  @Override
  public boolean wasNull() throws SQLException {
    checkOpen();
    return lastWasNull;
  }

  @Override
  public String getString(int columnIndex) throws SQLException {
    return Convert.str(value(columnIndex));
  }

  @Override
  public String getString(String columnLabel) throws SQLException {
    return Convert.str(value(columnLabel));
  }

  @Override
  public String getNString(int columnIndex) throws SQLException {
    return getString(columnIndex);
  }

  @Override
  public String getNString(String columnLabel) throws SQLException {
    return getString(columnLabel);
  }

  @Override
  public boolean getBoolean(int columnIndex) throws SQLException {
    return Convert.boolVal(value(columnIndex));
  }

  @Override
  public boolean getBoolean(String columnLabel) throws SQLException {
    return getBoolean(findColumn(columnLabel));
  }

  @Override
  public byte getByte(int columnIndex) throws SQLException {
    return (byte) Convert.longVal(value(columnIndex));
  }

  @Override
  public byte getByte(String columnLabel) throws SQLException {
    return getByte(findColumn(columnLabel));
  }

  @Override
  public short getShort(int columnIndex) throws SQLException {
    return (short) Convert.longVal(value(columnIndex));
  }

  @Override
  public short getShort(String columnLabel) throws SQLException {
    return getShort(findColumn(columnLabel));
  }

  @Override
  public int getInt(int columnIndex) throws SQLException {
    return (int) Convert.longVal(value(columnIndex));
  }

  @Override
  public int getInt(String columnLabel) throws SQLException {
    return getInt(findColumn(columnLabel));
  }

  @Override
  public long getLong(int columnIndex) throws SQLException {
    return Convert.longVal(value(columnIndex));
  }

  @Override
  public long getLong(String columnLabel) throws SQLException {
    return getLong(findColumn(columnLabel));
  }

  @Override
  public float getFloat(int columnIndex) throws SQLException {
    return (float) Convert.doubleVal(value(columnIndex));
  }

  @Override
  public float getFloat(String columnLabel) throws SQLException {
    return getFloat(findColumn(columnLabel));
  }

  @Override
  public double getDouble(int columnIndex) throws SQLException {
    return Convert.doubleVal(value(columnIndex));
  }

  @Override
  public double getDouble(String columnLabel) throws SQLException {
    return getDouble(findColumn(columnLabel));
  }

  @Override
  public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
    return Convert.bigDecimal(value(columnIndex));
  }

  @Override
  public BigDecimal getBigDecimal(String columnLabel) throws SQLException {
    return getBigDecimal(findColumn(columnLabel));
  }

  @Override
  @Deprecated
  public BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException {
    BigDecimal value = getBigDecimal(columnIndex);
    return value == null ? null : value.setScale(scale, RoundingMode.HALF_UP);
  }

  @Override
  @Deprecated
  public BigDecimal getBigDecimal(String columnLabel, int scale) throws SQLException {
    return getBigDecimal(findColumn(columnLabel), scale);
  }

  @Override
  public byte[] getBytes(int columnIndex) throws SQLException {
    return Convert.bytes(value(columnIndex));
  }

  @Override
  public byte[] getBytes(String columnLabel) throws SQLException {
    return getBytes(findColumn(columnLabel));
  }

  @Override
  public Date getDate(int columnIndex) throws SQLException {
    return Convert.as(value(columnIndex), Date.class);
  }

  @Override
  public Date getDate(String columnLabel) throws SQLException {
    return getDate(findColumn(columnLabel));
  }

  @Override
  public Date getDate(int columnIndex, java.util.Calendar calendar) throws SQLException {
    return getDate(columnIndex);
  }

  @Override
  public Date getDate(String columnLabel, java.util.Calendar calendar) throws SQLException {
    return getDate(columnLabel);
  }

  @Override
  public Time getTime(int columnIndex) throws SQLException {
    return Convert.as(value(columnIndex), Time.class);
  }

  @Override
  public Time getTime(String columnLabel) throws SQLException {
    return getTime(findColumn(columnLabel));
  }

  @Override
  public Time getTime(int columnIndex, java.util.Calendar calendar) throws SQLException {
    return getTime(columnIndex);
  }

  @Override
  public Time getTime(String columnLabel, java.util.Calendar calendar) throws SQLException {
    return getTime(columnLabel);
  }

  @Override
  public Timestamp getTimestamp(int columnIndex) throws SQLException {
    return Convert.as(value(columnIndex), Timestamp.class);
  }

  @Override
  public Timestamp getTimestamp(String columnLabel) throws SQLException {
    return getTimestamp(findColumn(columnLabel));
  }

  @Override
  public Timestamp getTimestamp(int columnIndex, java.util.Calendar calendar) throws SQLException {
    return getTimestamp(columnIndex);
  }

  @Override
  public Timestamp getTimestamp(String columnLabel, java.util.Calendar calendar)
      throws SQLException {
    return getTimestamp(columnLabel);
  }

  @Override
  public Object getObject(int columnIndex) throws SQLException {
    return value(columnIndex);
  }

  @Override
  public Object getObject(String columnLabel) throws SQLException {
    return value(columnLabel);
  }

  @Override
  public Object getObject(int columnIndex, Map<String, Class<?>> map) throws SQLException {
    return getObject(columnIndex);
  }

  @Override
  public Object getObject(String columnLabel, Map<String, Class<?>> map) throws SQLException {
    return getObject(columnLabel);
  }

  @Override
  public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
    return Convert.as(value(columnIndex), type);
  }

  @Override
  public <T> T getObject(String columnLabel, Class<T> type) throws SQLException {
    return getObject(findColumn(columnLabel), type);
  }

  @Override
  public InputStream getAsciiStream(int columnIndex) throws SQLException {
    byte[] bytes = getBytes(columnIndex);
    return bytes == null ? null : new ByteArrayInputStream(bytes);
  }

  @Override
  public InputStream getAsciiStream(String columnLabel) throws SQLException {
    return getAsciiStream(findColumn(columnLabel));
  }

  @Override
  @Deprecated
  public InputStream getUnicodeStream(int columnIndex) throws SQLException {
    return getAsciiStream(columnIndex);
  }

  @Override
  @Deprecated
  public InputStream getUnicodeStream(String columnLabel) throws SQLException {
    return getAsciiStream(columnLabel);
  }

  @Override
  public InputStream getBinaryStream(int columnIndex) throws SQLException {
    return getAsciiStream(columnIndex);
  }

  @Override
  public InputStream getBinaryStream(String columnLabel) throws SQLException {
    return getBinaryStream(findColumn(columnLabel));
  }

  @Override
  public Reader getCharacterStream(int columnIndex) throws SQLException {
    String value = getString(columnIndex);
    return value == null ? null : new StringReader(value);
  }

  @Override
  public Reader getCharacterStream(String columnLabel) throws SQLException {
    return getCharacterStream(findColumn(columnLabel));
  }

  @Override
  public Reader getNCharacterStream(int columnIndex) throws SQLException {
    return getCharacterStream(columnIndex);
  }

  @Override
  public Reader getNCharacterStream(String columnLabel) throws SQLException {
    return getNCharacterStream(findColumn(columnLabel));
  }

  @Override
  public URL getURL(int columnIndex) throws SQLException {
    String value = getString(columnIndex);
    if (value == null) {
      return null;
    }
    try {
      return new URL(value);
    } catch (java.net.MalformedURLException e) {
      throw new SQLException("Invalid URL: " + value, "22018", e);
    }
  }

  @Override
  public URL getURL(String columnLabel) throws SQLException {
    return getURL(findColumn(columnLabel));
  }

  // ---------------------------------------------------------------- state

  @Override
  public Statement getStatement() throws SQLException {
    checkOpen();
    return statement;
  }

  @Override
  public int getRow() throws SQLException {
    checkOpen();
    return rowNumber;
  }

  @Override
  public boolean isBeforeFirst() throws SQLException {
    checkOpen();
    return rowNumber == 0 && !done;
  }

  @Override
  public boolean isAfterLast() throws SQLException {
    checkOpen();
    return done && rowNumber > 0;
  }

  @Override
  public boolean isFirst() throws SQLException {
    checkOpen();
    return rowNumber == 1;
  }

  @Override
  public boolean isLast() throws SQLException {
    checkOpen();
    return false;
  }

  @Override
  public void close() throws SQLException {
    if (closed) {
      return;
    }
    closed = true;
    current = null;
    closeQuietly();
    if (statement != null) {
      statement.resultSetClosed(this);
    }
  }

  @Override
  public boolean isClosed() {
    return closed;
  }

  @Override
  public int getType() {
    return ResultSet.TYPE_FORWARD_ONLY;
  }

  @Override
  public int getConcurrency() {
    return ResultSet.CONCUR_READ_ONLY;
  }

  @Override
  public int getHoldability() {
    return ResultSet.HOLD_CURSORS_OVER_COMMIT;
  }

  @Override
  public int getFetchDirection() {
    return ResultSet.FETCH_FORWARD;
  }

  @Override
  public void setFetchDirection(int direction) throws SQLException {
    if (direction != ResultSet.FETCH_FORWARD) {
      throw new SQLFeatureNotSupportedException("Only FETCH_FORWARD is supported");
    }
  }

  @Override
  public int getFetchSize() {
    return 0;
  }

  @Override
  public void setFetchSize(int rows) throws SQLException {
    if (rows < 0) {
      throw new SQLException("Fetch size cannot be negative");
    }
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
  public String getCursorName() throws SQLException {
    throw new SQLFeatureNotSupportedException("Named cursors are not supported");
  }

  @Override
  public boolean rowUpdated() {
    return false;
  }

  @Override
  public boolean rowInserted() {
    return false;
  }

  @Override
  public boolean rowDeleted() {
    return false;
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

  private void checkOpen() throws SQLException {
    if (closed) {
      throw new SQLException("Result set is closed", "24000");
    }
  }

  private void closeQuietly() {
    if (stream != null) {
      stream.close();
    }
  }
}

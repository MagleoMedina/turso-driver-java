package io.turso.jdbc;

import io.turso.jdbc.protocol.Json;
import io.turso.jdbc.protocol.Stmt;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLType;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Prepared statement. SQL text is sent on every execution; parameters are bound positionally. */
public final class TursoPreparedStatement extends TursoPreparedSupport implements PreparedStatement {
  private final String sql;
  private final TreeMap<Integer, Object> parameters = new TreeMap<>();
  private Map<String, Object> description;
  private TursoResultSetMetaData metaData;
  private TursoParameterMetaData parameterMetaData;

  TursoPreparedStatement(TursoConnection connection, String sql) {
    super(connection);
    this.sql = sql;
  }

  // ---------------------------------------------------------------- execution

  @Override
  public boolean execute() throws SQLException {
    checkOpen();
    closeCurrent();
    checkOpen();
    lastSql = sql;
    TursoConnection.Outcome outcome = connection.execute(this, build(true), maxRows, timeout());
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
  public ResultSet executeQuery() throws SQLException {
    checkOpen();
    closeCurrent();
    checkOpen();
    lastSql = sql;
    TursoConnection.Outcome outcome = connection.execute(this, build(true), maxRows, timeout());
    if (outcome.resultSet() == null) {
      throw new SQLException("Statement did not return a result set", "HY000");
    }
    resultSet = outcome.resultSet();
    updateCount = -1;
    return resultSet;
  }

  @Override
  public int executeUpdate() throws SQLException {
    long count = executeLargeUpdate();
    if (count > Integer.MAX_VALUE) {
      throw new SQLException("Update count exceeds Integer.MAX_VALUE", "22003");
    }
    return (int) count;
  }

  @Override
  public long executeLargeUpdate() throws SQLException {
    checkOpen();
    closeCurrent();
    checkOpen();
    lastSql = sql;
    TursoConnection.Outcome outcome = connection.update(this, build(false), timeout());
    updateCount = outcome.updateCount();
    setGeneratedKeys(outcome.lastInsertRowid());
    return updateCount;
  }

  @Override
  public void addBatch() throws SQLException {
    checkOpen();
    preparedBatch.add(build(false));
  }

  @Override
  public void clearParameters() throws SQLException {
    checkOpen();
    parameters.clear();
  }

  private Stmt build(boolean wantRows) throws SQLException {
    if (parameters.isEmpty()) {
      return Stmt.of(sql, List.of(), wantRows);
    }
    int max = parameters.lastKey();
    List<Object> args = new ArrayList<>(max);
    for (int i = 1; i <= max; i++) {
      args.add(Convert.toParam(parameters.get(i)));
    }
    return Stmt.of(sql, args, wantRows);
  }

  // ---------------------------------------------------------------- metadata

  @Override
  public ResultSetMetaData getMetaData() throws SQLException {
    checkOpen();
    if (metaData == null) {
      List<Object> cols = Json.array(describe().getOrDefault("cols", List.of()));
      List<Column> columns = new ArrayList<>(cols.size());
      for (Object col : cols) {
        Map<String, Object> c = Json.object(col);
        columns.add(Column.of(Json.string(c.get("name")), Json.string(c.get("decltype"))));
      }
      metaData = new TursoResultSetMetaData(columns);
    }
    return metaData;
  }

  @Override
  public ParameterMetaData getParameterMetaData() throws SQLException {
    checkOpen();
    if (parameterMetaData == null) {
      List<Object> params = Json.array(describe().getOrDefault("params", List.of()));
      parameterMetaData = new TursoParameterMetaData(params.size());
    }
    return parameterMetaData;
  }

  private Map<String, Object> describe() throws SQLException {
    if (description == null) {
      description = connection.describe(sql, timeout());
    }
    return description;
  }

  // ---------------------------------------------------------------- parameters

  private void param(int index, Object value) throws SQLException {
    checkOpen();
    if (index < 1) {
      throw new SQLException("Parameter index out of range: " + index, "07009");
    }
    parameters.put(index, value);
  }

  @Override
  public void setNull(int index, int sqlType) throws SQLException {
    param(index, null);
  }

  @Override
  public void setNull(int index, int sqlType, String typeName) throws SQLException {
    param(index, null);
  }

  @Override
  public void setBoolean(int index, boolean value) throws SQLException {
    param(index, value);
  }

  @Override
  public void setByte(int index, byte value) throws SQLException {
    param(index, value);
  }

  @Override
  public void setShort(int index, short value) throws SQLException {
    param(index, value);
  }

  @Override
  public void setInt(int index, int value) throws SQLException {
    param(index, value);
  }

  @Override
  public void setLong(int index, long value) throws SQLException {
    param(index, value);
  }

  @Override
  public void setFloat(int index, float value) throws SQLException {
    param(index, value);
  }

  @Override
  public void setDouble(int index, double value) throws SQLException {
    param(index, value);
  }

  @Override
  public void setBigDecimal(int index, BigDecimal value) throws SQLException {
    param(index, value);
  }

  @Override
  public void setString(int index, String value) throws SQLException {
    param(index, value);
  }

  @Override
  public void setNString(int index, String value) throws SQLException {
    param(index, value);
  }

  @Override
  public void setBytes(int index, byte[] value) throws SQLException {
    param(index, value);
  }

  @Override
  public void setDate(int index, Date value) throws SQLException {
    param(index, value);
  }

  @Override
  public void setDate(int index, Date value, java.util.Calendar calendar) throws SQLException {
    param(index, value);
  }

  @Override
  public void setTime(int index, Time value) throws SQLException {
    param(index, value);
  }

  @Override
  public void setTime(int index, Time value, java.util.Calendar calendar) throws SQLException {
    param(index, value);
  }

  @Override
  public void setTimestamp(int index, Timestamp value) throws SQLException {
    param(index, value);
  }

  @Override
  public void setTimestamp(int index, Timestamp value, java.util.Calendar calendar)
      throws SQLException {
    param(index, value);
  }

  @Override
  public void setObject(int index, Object value) throws SQLException {
    param(index, value);
  }

  @Override
  public void setObject(int index, Object value, int targetSqlType) throws SQLException {
    param(index, value);
  }

  @Override
  public void setObject(int index, Object value, int targetSqlType, int scaleOrLength)
      throws SQLException {
    param(index, value);
  }

  @Override
  public void setObject(int index, Object value, SQLType targetSqlType) throws SQLException {
    param(index, value);
  }

  @Override
  public void setObject(int index, Object value, SQLType targetSqlType, int scaleOrLength)
      throws SQLException {
    param(index, value);
  }

  @Override
  public void setAsciiStream(int index, InputStream value) throws SQLException {
    param(index, value);
  }

  @Override
  public void setAsciiStream(int index, InputStream value, int length) throws SQLException {
    param(index, value);
  }

  @Override
  public void setAsciiStream(int index, InputStream value, long length) throws SQLException {
    param(index, value);
  }

  @Override
  public void setUnicodeStream(int index, InputStream value, int length) throws SQLException {
    param(index, value);
  }

  @Override
  public void setBinaryStream(int index, InputStream value) throws SQLException {
    param(index, value);
  }

  @Override
  public void setBinaryStream(int index, InputStream value, int length) throws SQLException {
    param(index, value);
  }

  @Override
  public void setBinaryStream(int index, InputStream value, long length) throws SQLException {
    param(index, value);
  }

  @Override
  public void setCharacterStream(int index, Reader value) throws SQLException {
    param(index, value);
  }

  @Override
  public void setCharacterStream(int index, Reader value, int length) throws SQLException {
    param(index, value);
  }

  @Override
  public void setCharacterStream(int index, Reader value, long length) throws SQLException {
    param(index, value);
  }

  @Override
  public void setNCharacterStream(int index, Reader value) throws SQLException {
    param(index, value);
  }

  @Override
  public void setNCharacterStream(int index, Reader value, long length) throws SQLException {
    param(index, value);
  }

  @Override
  public void setBlob(int index, Blob value) throws SQLException {
    param(index, value);
  }

  @Override
  public void setBlob(int index, InputStream value) throws SQLException {
    param(index, value);
  }

  @Override
  public void setBlob(int index, InputStream value, long length) throws SQLException {
    param(index, value);
  }

  @Override
  public void setClob(int index, Clob value) throws SQLException {
    param(index, value);
  }

  @Override
  public void setClob(int index, Reader value) throws SQLException {
    param(index, value);
  }

  @Override
  public void setClob(int index, Reader value, long length) throws SQLException {
    param(index, value);
  }

  @Override
  public void setNClob(int index, NClob value) throws SQLException {
    param(index, value);
  }

  @Override
  public void setNClob(int index, Reader value) throws SQLException {
    param(index, value);
  }

  @Override
  public void setNClob(int index, Reader value, long length) throws SQLException {
    param(index, value);
  }

  @Override
  public void setURL(int index, URL value) throws SQLException {
    param(index, value == null ? null : value.toString());
  }

  @Override
  public void setSQLXML(int index, SQLXML value) throws SQLException {
    param(index, value == null ? null : value.getString());
  }

  @Override
  public void setRef(int index, Ref value) throws SQLException {
    throw new SQLFeatureNotSupportedException("REF parameters are not supported");
  }

  @Override
  public void setRowId(int index, RowId value) throws SQLException {
    throw new SQLFeatureNotSupportedException("ROWID parameters are not supported");
  }

  @Override
  public void setArray(int index, Array value) throws SQLException {
    throw new SQLFeatureNotSupportedException("ARRAY parameters are not supported");
  }

  // ---------------------------------------------------------------- helpers

}

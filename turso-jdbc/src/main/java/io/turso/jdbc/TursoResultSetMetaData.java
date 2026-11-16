package io.turso.jdbc;

import io.turso.jdbc.base.AbstractResultSetMetaData;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.List;

/** Result set metadata built from the protocol's column descriptions. */
public final class TursoResultSetMetaData extends AbstractResultSetMetaData {
  private final List<Column> columns;

  public TursoResultSetMetaData(List<Column> columns) {
    this.columns = columns;
  }

  @Override
  public int getColumnCount() {
    return columns.size();
  }

  @Override
  public boolean isAutoIncrement(int column) {
    return false;
  }

  @Override
  public boolean isCaseSensitive(int column) {
    return true;
  }

  @Override
  public boolean isSearchable(int column) {
    return true;
  }

  @Override
  public boolean isCurrency(int column) {
    return false;
  }

  @Override
  public int isNullable(int column) {
    return ResultSetMetaData.columnNullableUnknown;
  }

  @Override
  public boolean isSigned(int column) {
    return TypeMapper.isSigned(type(column));
  }

  @Override
  public int getColumnDisplaySize(int column) {
    return 0;
  }

  @Override
  public String getColumnLabel(int column) {
    return columns.get(column - 1).name();
  }

  @Override
  public String getColumnName(int column) {
    return columns.get(column - 1).name();
  }

  @Override
  public String getSchemaName(int column) {
    return "";
  }

  @Override
  public int getPrecision(int column) {
    return 0;
  }

  @Override
  public int getScale(int column) {
    return 0;
  }

  @Override
  public String getTableName(int column) {
    return "";
  }

  @Override
  public String getCatalogName(int column) {
    return "";
  }

  @Override
  public int getColumnType(int column) {
    return type(column);
  }

  @Override
  public String getColumnTypeName(int column) {
    String decltype = columns.get(column - 1).decltype();
    return decltype == null || decltype.isBlank() ? "ANY" : decltype;
  }

  @Override
  public boolean isReadOnly(int column) {
    return true;
  }

  @Override
  public boolean isWritable(int column) {
    return false;
  }

  @Override
  public boolean isDefinitelyWritable(int column) {
    return false;
  }

  @Override
  public String getColumnClassName(int column) {
    return TypeMapper.className(type(column));
  }

  private int type(int column) {
    if (column < 1 || column > columns.size()) {
      return java.sql.Types.OTHER;
    }
    return columns.get(column - 1).sqlType();
  }
}

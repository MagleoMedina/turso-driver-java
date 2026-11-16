package io.turso.jdbc;

import io.turso.jdbc.base.AbstractParameterMetaData;
import java.sql.ParameterMetaData;
import java.sql.SQLException;
import java.sql.Types;

/** Parameter metadata from a server-side describe. */
public final class TursoParameterMetaData extends AbstractParameterMetaData {
  private final int count;

  public TursoParameterMetaData(int count) {
    this.count = count;
  }

  @Override
  public int getParameterCount() {
    return count;
  }

  @Override
  public int isNullable(int param) throws SQLException {
    check(param);
    return ParameterMetaData.parameterNullableUnknown;
  }

  @Override
  public boolean isSigned(int param) throws SQLException {
    check(param);
    return true;
  }

  @Override
  public int getPrecision(int param) throws SQLException {
    check(param);
    return 0;
  }

  @Override
  public int getScale(int param) throws SQLException {
    check(param);
    return 0;
  }

  @Override
  public int getParameterType(int param) throws SQLException {
    check(param);
    return Types.OTHER;
  }

  @Override
  public String getParameterTypeName(int param) throws SQLException {
    check(param);
    return "ANY";
  }

  @Override
  public String getParameterClassName(int param) throws SQLException {
    check(param);
    return Object.class.getName();
  }

  @Override
  public int getParameterMode(int param) throws SQLException {
    check(param);
    return ParameterMetaData.parameterModeIn;
  }

  private void check(int param) throws SQLException {
    if (param < 1 || param > count) {
      throw new SQLException("Parameter index out of range: " + param, "07009");
    }
  }
}

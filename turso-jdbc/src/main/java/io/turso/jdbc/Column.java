package io.turso.jdbc;

import java.sql.Types;

/** A result column: name, declared type and mapped JDBC type. */
public record Column(String name, String decltype, int sqlType) {

  public static Column of(String name, String decltype) {
    return new Column(name == null ? "" : name, decltype, TypeMapper.sqlType(decltype));
  }
}

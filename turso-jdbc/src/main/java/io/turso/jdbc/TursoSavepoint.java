package io.turso.jdbc;

import java.sql.SQLException;
import java.sql.Savepoint;

/** Named SQLite savepoint. */
public final class TursoSavepoint implements Savepoint {
  private final int id;
  private final String name;
  private boolean valid = true;

  public TursoSavepoint(int id, String name) {
    this.id = id;
    this.name = name;
  }

  @Override
  public int getSavepointId() throws SQLException {
    if (name != null) {
      throw new SQLException("Named savepoints do not have an id");
    }
    return id;
  }

  @Override
  public String getSavepointName() throws SQLException {
    if (name == null) {
      throw new SQLException("Unnamed savepoints do not have a name");
    }
    return name;
  }

  public String name() {
    return name != null ? name : "turso_sp_" + id;
  }

  boolean isValid() {
    return valid;
  }

  void invalidate() {
    valid = false;
  }
}

package io.turso.jdbc.protocol;

/** One step of a batch: a statement plus an optional guard. */
public record Step(Condition condition, Stmt stmt) {

  public static Step of(Stmt stmt) {
    return new Step(null, stmt);
  }

  public static Step of(Condition condition, Stmt stmt) {
    return new Step(condition, stmt);
  }

  public void write(Json.Writer w) {
    w.beginObject();
    if (condition != null) {
      w.name("condition");
      condition.write(w);
    }
    w.name("stmt");
    stmt.write(w);
    w.endObject();
  }
}

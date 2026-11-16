package io.turso.jdbc.protocol;

import java.util.List;

/** A condition that guards the execution of a batch step. */
public sealed interface Condition {

  record Ok(int step) implements Condition {}

  record Error(int step) implements Condition {}

  record Not(Condition cond) implements Condition {}

  record And(List<Condition> conds) implements Condition {}

  record Or(List<Condition> conds) implements Condition {}

  record IsAutoCommit() implements Condition {}

  static Ok ok(int step) {
    return new Ok(step);
  }

  static Error error(int step) {
    return new Error(step);
  }

  static Not not(Condition cond) {
    return new Not(cond);
  }

  static And and(Condition... conds) {
    return new And(List.of(conds));
  }

  static Or or(Condition... conds) {
    return new Or(List.of(conds));
  }

  static IsAutoCommit isAutoCommit() {
    return new IsAutoCommit();
  }

  default void write(Json.Writer w) {
    w.beginObject();
    switch (this) {
      case Ok ok -> w.name("type").value("ok").name("step").value(ok.step());
      case Error err -> w.name("type").value("error").name("step").value(err.step());
      case Not not -> {
        w.name("type").value("not");
        w.name("cond");
        not.cond().write(w);
      }
      case And and -> {
        w.name("type").value("and");
        writeList(w, and.conds());
      }
      case Or or -> {
        w.name("type").value("or");
        writeList(w, or.conds());
      }
      case IsAutoCommit ignored -> w.name("type").value("is_autocommit");
    }
    w.endObject();
  }

  private static void writeList(Json.Writer w, List<Condition> conds) {
    w.name("conds").beginArray();
    for (Condition c : conds) {
      c.write(w);
    }
    w.endArray();
  }
}

package io.turso.jdbc.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A single SQL statement with optional positional or named arguments. */
public final class Stmt {
  private final String sql;
  private final List<Object> args;
  private final Map<String, Object> namedArgs;
  private final boolean wantRows;

  private Stmt(String sql, List<Object> args, Map<String, Object> namedArgs, boolean wantRows) {
    this.sql = sql;
    this.args = args == null ? List.of() : args;
    this.namedArgs = namedArgs == null ? Map.of() : namedArgs;
    this.wantRows = wantRows;
  }

  public static Stmt of(String sql) {
    return new Stmt(sql, List.of(), Map.of(), true);
  }

  public static Stmt of(String sql, List<Object> args) {
    return new Stmt(sql, args, Map.of(), true);
  }

  public static Stmt of(String sql, List<Object> args, boolean wantRows) {
    return new Stmt(sql, args, Map.of(), wantRows);
  }

  public static Stmt named(String sql, Map<String, Object> namedArgs) {
    return new Stmt(sql, List.of(), new LinkedHashMap<>(namedArgs), true);
  }

  public String sql() {
    return sql;
  }

  public boolean wantRows() {
    return wantRows;
  }

  public List<Object> args() {
    return args;
  }

  public Map<String, Object> namedArgs() {
    return namedArgs;
  }

  public Stmt withWantRows(boolean want) {
    return new Stmt(sql, args, namedArgs, want);
  }

  public void write(Json.Writer w) {
    w.beginObject();
    w.name("sql").value(sql);
    if (!args.isEmpty()) {
      w.name("args").beginArray();
      for (Object arg : args) {
        Values.encode(w, arg);
      }
      w.endArray();
    }
    if (!namedArgs.isEmpty()) {
      w.name("named_args").beginArray();
      for (Map.Entry<String, Object> e : namedArgs.entrySet()) {
        Values.encodeNamed(w, e.getKey(), e.getValue());
      }
      w.endArray();
    }
    if (!wantRows) {
      w.name("want_rows").value(false);
    }
    w.endObject();
  }

  /** Builds a copy of this statement with a normalized positional argument list. */
  public Stmt withArgs(List<Object> newArgs) {
    return new Stmt(sql, new ArrayList<>(newArgs), Map.of(), wantRows);
  }
}

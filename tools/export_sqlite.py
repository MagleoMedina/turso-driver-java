#!/usr/bin/env python3
"""Exporta un archivo SQLite al JSON que consume CierreImportIT.

Uso:
  python tools/export_sqlite.py cierre.db /tmp/cierre-dump.json

El JSON contiene el DDL de tablas/índices/triggers/vistas y las filas en orden
canónico (PK o rowid), con blobs en base64.
"""
import base64
import json
import sqlite3
import sys


def ident(name: str) -> str:
    return '"' + name.replace('"', '""') + '"'


def encode(value):
    if isinstance(value, bytes):
        return {"__blob__": base64.b64encode(value).decode("ascii")}
    return value


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    src, out = sys.argv[1], sys.argv[2]
    con = sqlite3.connect(f"file:{src}?mode=ro", uri=True)
    cur = con.cursor()

    integrity = cur.execute("PRAGMA integrity_check").fetchone()[0]
    if integrity != "ok":
        raise SystemExit(f"integrity_check failed: {integrity}")

    tables = [r[0] for r in cur.execute(
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")]
    deps = {}
    for table in tables:
        refs = [r[2] for r in cur.execute(f"PRAGMA foreign_key_list({ident(table)})")]
        deps[table] = [r for r in refs if r in tables and r != table]

    ordered, seen, visiting = [], set(), set()

    def visit(table: str) -> None:
        if table in seen:
            return
        if table in visiting:
            raise SystemExit(f"FK cycle around {table}")
        visiting.add(table)
        for dep in deps.get(table, []):
            visit(dep)
        visiting.remove(table)
        seen.add(table)
        ordered.append(table)

    for table in tables:
        visit(table)

    result = {"tables": [], "otherSchema": []}
    for table in ordered:
        sql = cur.execute(
            "SELECT sql FROM sqlite_master WHERE type='table' AND name=?", (table,)
        ).fetchone()[0]
        info = cur.execute(f"PRAGMA table_xinfo({ident(table)})").fetchall()
        columns = [r[1] for r in info if r[6] == 0]  # hidden != 0 => generated/virtual
        pk = [r[1] for r in sorted((r for r in info if r[5] > 0), key=lambda r: r[5])]
        order_by = ", ".join(ident(c) for c in pk) if pk else "rowid"
        select = ", ".join(ident(c) for c in columns)
        rows = [[encode(v) for v in row] for row in cur.execute(
            f"SELECT {select} FROM {ident(table)} ORDER BY {order_by}")]
        result["tables"].append({
            "name": table,
            "sql": sql,
            "columns": columns,
            "primaryKey": pk,
            "orderBy": order_by,
            "rows": rows,
        })

    for kind in ("index", "trigger", "view"):
        for (sql,) in cur.execute(
                "SELECT sql FROM sqlite_master WHERE type=? AND sql IS NOT NULL ORDER BY name",
                (kind,)):
            result["otherSchema"].append(sql)

    with open(out, "w", encoding="utf-8") as handle:
        json.dump(result, handle, ensure_ascii=False)

    for table in result["tables"]:
        print(f"{table['name']}: {len(table['rows'])} rows, {len(table['columns'])} columns")
    print(f"other schema: {len(result['otherSchema'])}")
    print(f"dump: {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

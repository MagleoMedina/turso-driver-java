package io.turso.jdbc.protocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Map;

/** Incremental reader over the newline-delimited JSON cursor response. */
public final class CursorStream implements AutoCloseable {
  private final InputStream in;
  private final BufferedReader reader;
  private boolean closed;

  CursorStream(InputStream in) {
    this.in = in;
    this.reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
  }

  /** Returns the next entry, or null at end of stream. */
  public Map<String, Object> next() throws SQLException {
    if (closed) {
      return null;
    }
    try {
      while (true) {
        String line = reader.readLine();
        if (line == null) {
          return null;
        }
        line = line.trim();
        if (line.isEmpty()) {
          continue;
        }
        try {
          return Json.object(Json.parse(line));
        } catch (RuntimeException e) {
          throw TursoErrors.truncated("Invalid cursor entry: " + e.getMessage());
        }
      }
    } catch (IOException e) {
      throw TursoErrors.truncated("Cursor read failed: " + e.getMessage());
    }
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    try {
      in.close();
    } catch (IOException ignored) {
      // best effort
    }
  }
}

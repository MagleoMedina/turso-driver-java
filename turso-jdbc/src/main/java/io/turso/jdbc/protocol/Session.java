package io.turso.jdbc.protocol;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * A logical Turso server session: an HTTP/1.1 stream identified by an opaque baton.
 * Not thread-safe; a JDBC Connection serializes access.
 */
public final class Session implements AutoCloseable {
  private static final String USER_AGENT = "turso-jdbc/0.1.0";

  private final HttpClient http;
  private final String token;
  private final String encryptionKey;
  private final Duration requestTimeout;
  private final String initialUrl;

  private String baseUrl;
  private String baton;
  private boolean closed;

  public Session(String url, String token, String encryptionKey, Duration connectTimeout,
      Duration requestTimeout) {
    this.initialUrl = normalizeUrl(url);
    this.baseUrl = initialUrl;
    this.token = token == null || token.isBlank() ? null : token;
    this.encryptionKey = encryptionKey == null || encryptionKey.isBlank() ? null : encryptionKey;
    this.requestTimeout = requestTimeout;
    this.http = HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
  }

  public String url() {
    return initialUrl;
  }

  public String baton() {
    return baton;
  }

  public boolean hasStream() {
    return baton != null;
  }

  /** Executes a pipeline. {@code allowReconnect} retries once on an expired stream (autocommit only). */
  public Map<String, Object> pipeline(Json.Body requests, boolean allowReconnect) throws SQLException {
    return pipeline(requests, allowReconnect, null);
  }

  public Map<String, Object> pipeline(Json.Body requests, boolean allowReconnect, Duration timeout)
      throws SQLException {
    int attempts = allowReconnect ? 2 : 1;
    for (int attempt = 0; attempt < attempts; attempt++) {
      String usedBaton = baton;
      String body = Json.write(w -> {
        w.beginObject();
        w.name("baton").value(usedBaton);
        w.name("requests").beginArray();
        requests.write(w);
        w.endArray();
        w.endObject();
      });
      HttpResponse<String> response;
      try {
        response = http.send(request("/v3/pipeline", body, timeout),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      } catch (IOException e) {
        baton = null;
        throw new java.sql.SQLTransientConnectionException(
            "I/O error talking to Turso: " + e.getMessage(), "08006", e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new java.sql.SQLTransientConnectionException("Interrupted", "08006", e);
      }
      if (response.statusCode() == 200) {
        Map<String, Object> parsed = parseObject(response.body());
        updateStream(parsed);
        return parsed;
      }
      if (response.statusCode() == 404 && usedBaton != null && allowReconnect && attempt + 1 < attempts) {
        baton = null;
        continue;
      }
      if (usedBaton != null) {
        baton = null;
        throw TursoErrors.streamExpired("The server lost the stream (HTTP 404)");
      }
      throw TursoErrors.http(response.statusCode(), response.body(), null);
    }
    throw TursoErrors.http(404, "stream not found", null);
  }

  /** Opens a cursor and streams its entries. The baton line is consumed eagerly. */
  public CursorStream cursor(List<Step> steps, boolean allowReconnect) throws SQLException {
    return cursor(steps, allowReconnect, null);
  }

  public CursorStream cursor(List<Step> steps, boolean allowReconnect, Duration timeout)
      throws SQLException {
    int attempts = allowReconnect ? 2 : 1;
    for (int attempt = 0; attempt < attempts; attempt++) {
      String usedBaton = baton;
      String body = Json.write(w -> {
        w.beginObject();
        w.name("baton").value(usedBaton);
        w.name("batch").beginObject();
        w.name("steps").beginArray();
        for (Step step : steps) {
          step.write(w);
        }
        w.endArray();
        w.endObject();
        w.endObject();
      });
      HttpResponse<java.io.InputStream> response;
      try {
        response = http.send(request("/v3/cursor", body, timeout),
            HttpResponse.BodyHandlers.ofInputStream());
      } catch (IOException e) {
        baton = null;
        throw new java.sql.SQLTransientConnectionException(
            "I/O error talking to Turso: " + e.getMessage(), "08006", e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new java.sql.SQLTransientConnectionException("Interrupted", "08006", e);
      }
      java.io.InputStream in = response.body();
      if (response.statusCode() != 200) {
        String errorBody;
        try (in) {
          errorBody = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
          errorBody = "";
        }
        if (response.statusCode() == 404 && usedBaton != null && allowReconnect && attempt + 1 < attempts) {
          baton = null;
          continue;
        }
        if (usedBaton != null) {
          baton = null;
          throw TursoErrors.streamExpired("The server lost the stream (HTTP 404)");
        }
        throw TursoErrors.http(response.statusCode(), errorBody, null);
      }
      CursorStream stream = new CursorStream(in);
      Map<String, Object> header;
      try {
        header = stream.next();
      } catch (SQLException e) {
        stream.close();
        throw e;
      }
      if (header == null || (header.get("baton") == null && header.containsKey("error"))) {
        stream.close();
        throw TursoErrors.truncated("Cursor response did not start with a baton");
      }
      updateStream(header);
      return stream;
    }
    throw TursoErrors.http(404, "stream not found", null);
  }

  private HttpRequest request(String path, String body, Duration timeout) {
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
        .timeout(timeout != null ? timeout : requestTimeout)
        .header("Content-Type", "application/json")
        .header("User-Agent", USER_AGENT)
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    if (encryptionKey != null) {
      builder.header("x-turso-encryption-key", encryptionKey);
    }
    return builder.build();
  }

  private void updateStream(Map<String, Object> body) {
    Object newBaton = body.get("baton");
    baton = newBaton instanceof String s && !s.isEmpty() ? s : null;
    Object newBase = body.get("base_url");
    if (newBase instanceof String s && !s.isBlank()) {
      baseUrl = normalizeUrl(s);
    }
  }

  private static Map<String, Object> parseObject(String body) throws SQLException {
    try {
      return Json.object(Json.parse(body));
    } catch (RuntimeException e) {
      throw TursoErrors.truncated("Invalid JSON from Turso: " + e.getMessage());
    }
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    String current = baton;
    baton = null;
    if (current == null) {
      return;
    }
    String body = Json.write(w -> {
      w.beginObject();
      w.name("baton").value(current);
      w.name("requests").beginArray();
      w.beginObject().name("type").value("close").endObject();
      w.endArray();
      w.endObject();
    });
    try {
      HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + "/v3/pipeline"))
          .timeout(Duration.ofSeconds(5))
          .header("Content-Type", "application/json")
          .header("User-Agent", USER_AGENT)
          .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
      if (token != null) {
        request = request.header("Authorization", "Bearer " + token);
      }
      http.send(request.build(), HttpResponse.BodyHandlers.discarding());
    } catch (IOException | InterruptedException ignored) {
      if (ignored instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
    }
  }

  public static String normalizeUrl(String url) {
    String value = url.trim();
    if (value.startsWith("libsql://") || value.startsWith("turso://")) {
      value = "https://" + value.substring(value.indexOf("://") + 3);
    } else if (!value.startsWith("http://") && !value.startsWith("https://")) {
      value = "https://" + value;
    }
    while (value.endsWith("/")) {
      value = value.substring(0, value.length() - 1);
    }
    return value;
  }
}

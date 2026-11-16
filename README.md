# turso-jdbc

Driver JDBC **Java puro** para Turso Cloud sobre el protocolo *SQL over HTTP v3*
(`/v3/pipeline` y `/v3/cursor`, con streaming NDJSON). Sin JNI, sin binarios por
plataforma, sin dependencias de runtime.

```
Java 21+  ·  sin dependencias  ·  Hikari  ·  JdbcClient  ·  JPA/Hibernate
```

## Qué resuelve

- Un jar que funciona en Windows, macOS, Linux y ARM (no hay `.so`/`.dll`).
- Integración real con Spring Boot: `DataSource`, `JdbcTemplate`/`JdbcClient`,
  `@Transactional`, `spring.sql.init`, Actuator.
- `ResultSet` **en streaming** con `/v3/cursor` (sin bufferizar filas).
- Transacciones, savepoints y `executeBatch` **atómico en un solo round-trip**.
- `getGeneratedKeys` desde `last_insert_rowid` (identidad de Hibernate).
- Tokens por conexión (multi-tenant) vía `Properties`/`DataSource`.

## Instalación

```bash
mvn install
```

```xml
<dependency>
  <groupId>io.turso</groupId>
  <artifactId>turso-jdbc</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## Uso

```java
Properties props = new Properties();
props.setProperty("authToken", System.getenv("TURSO_TOKEN"));

try (Connection c = DriverManager.getConnection(
        "jdbc:turso:" + System.getenv("TURSO_URL"), props);
     Statement s = c.createStatement();
     ResultSet rs = s.executeQuery("select sqlite_version()")) {
  rs.next();
  System.out.println(rs.getString(1));
}
```

`TURSO_URL` puede ser `libsql://…`, `turso://…` o `https://…`. El token también
se puede pasar como `password` de JDBC (recomendado: no queda en la URL).

### Spring Boot

```yaml
spring:
  datasource:
    url: jdbc:turso:${TURSO_URL}
    driver-class-name: io.turso.jdbc.TursoDriver
    password: ${TURSO_TOKEN}
  sql:
    init:
      mode: always
  jpa:
    database-platform: org.hibernate.community.dialect.SQLiteDialect
    hibernate:
      ddl-auto: none
    properties:
      hibernate.jdbc.use_get_generated_keys: true
```

`driver-class-name` es obligatorio (Spring no conoce el esquema `jdbc:turso:`).
Para JPA añade `org.hibernate.orm:hibernate-community-dialects` alineado con tu
Hibernate (probado con 7.4.13 sobre Boot 4.1.2).

No hace falta starter: Hikari, Actuator y Spring Data se configuran solos.

## Opciones de URL

| Parámetro | Default | Descripción |
|---|---|---|
| `authToken` | — | Token de Turso (o `password`). |
| `encryptionKey` | — | Clave BYOK; se envía en `x-turso-encryption-key`. |
| `transactionMode` | `DEFERRED` | `DEFERRED`, `IMMEDIATE`, `EXCLUSIVE`, `CONCURRENT`. |
| `requestTimeoutMs` | `30000` | Timeout HTTP por petición. |
| `connectTimeoutMs` | `10000` | Timeout de conexión TCP. |
| `readOnly` | `false` | Marca la conexión como read-only (informativo). |

## Límites reales (del servidor, no del driver)

| Límite | Detalle |
|---|---|
| **Idle de transacción ≈ 10 s** | Si no hay actividad en el stream, el servidor revierte la transacción (`SQLITE_BUSY` in-band). No hay límite de duración total: una transacción activa puede durar más. Mantén los `@Transactional` cortos. |
| **Autocommit sin estado** | Un statement en autocommit no conserva sesión (el servidor devuelve `baton: null`). `TEMP`/`PRAGMA` sí la conservan… hasta el idle. |
| **1 round-trip por sentencia** | No hay pipeline entre llamadas JDBC. `executeBatch` sí agrupa N statements (y es atómico). |
| **Latencia = región** | Medido desde Sudamérica a `ap-northeast-1`: ~420 ms/sentencia. Crea el grupo en una región cercana; el driver no lo arregla. |
| **Sin cursores scrollables/actualizables** | `TYPE_FORWARD_ONLY` + `CONCUR_READ_ONLY`. |
| **`setMaxRows` corta el cursor en cliente** | Se cierra el body HTTP al llegar al límite; verificado que la transacción sigue viva. |
| **Streams de subida** | `setBinaryStream`/`setCharacterStream` leen todo en memoria. |
| **Dialecto SQLite** | Sin esquemas, secuencias, arrays, `FOR UPDATE`, XA ni stored procedures. `user_version`, `journal_mode` y `busy_timeout` no están soportados por el servidor. |
| **Metadata JDBC parcial** | Producto/versión/flags sí; `getTables`/`getColumns`/`getPrimaryKeys` devuelven vacío. No uses `hbm2ddl.validate` por metadata. |
| **Solo motor Turso nuevo (v3)** | DBs libSQL legacy (solo `/v2/pipeline`) no están soportadas. |

## Pruebas

```bash
# unitarias (sin red)
mvn -pl turso-jdbc test

# integración real (opt-in; crea/borra filas en it_turso_jdbc e it_demo_note)
TURSO_IT=true TURSO_URL=libsql://... TURSO_TOKEN=... mvn verify
```

Los IT se saltan solos si faltan las variables. `examples/spring-demo` demuestra
JdbcClient, JPA con identidad, `@Transactional` y batch.

### Importar una base SQLite real

`tools/export_sqlite.py` vuelca un archivo SQLite (schema + filas, blob en base64)
y `CierreImportIT` lo carga en la DB Turso configurada y verifica filas y SHA-256:

```bash
python tools/export_sqlite.py cierre.db /tmp/cierre-dump.json
export TURSO_URL=libsql://... TURSO_TOKEN=... CIERRE_DUMP=/tmp/cierre-dump.json
mvn -pl turso-jdbc verify -Dit.test=CierreImportIT
```

Ojo: el test hace `DROP TABLE` de las tablas del dump en la DB destino. Para
respaldar un SQLite en modo WAL usa la API de backup (`sqlite3.Connection.backup`),
no una copia del archivo a pelo.

## Arquitectura

```
turso-jdbc/
  protocol/   Json (sin dependencias), Values, Session (baton/base_url/retry),
              CursorStream (NDJSON incremental), Conditions, errores
  jdbc/       Driver, DataSource, Connection, Statement, PreparedStatement,
              ResultSet (+ metadata), Savepoint
  base/       stubs generados por tools/GenStubs.java (no editar)
```

Decisiones clave:

- Un `HttpClient` compartido por pool; HTTP/1.1 keep-alive.
- Se conserva el `baton` que devuelve el servidor y se reutiliza; si expira en
  autocommit se reintenta una vez con stream nuevo.
- `executeQuery`/`execute` usan `/v3/cursor`; `executeUpdate` usa `/v3/pipeline`.
- `setAutoCommit(false)` hace `BEGIN` perezoso con la primera sentencia.
- `executeBatch` usa `batch` con `BEGIN`/`COMMIT`/`ROLLBACK` condicionales.
- El cursor de Turso no manda terminador `replication_index`: EOF limpio se
  considera éxito (contando los pasos enviados).

## Fuera de alcance

Sync/embedded replicas, R2DBC/reactivo, XA, ResultSet actualizable, motor de
migraciones propio, publicación en Maven Central.

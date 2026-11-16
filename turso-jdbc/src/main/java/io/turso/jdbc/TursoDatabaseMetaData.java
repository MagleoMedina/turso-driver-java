package io.turso.jdbc;

import io.turso.jdbc.base.AbstractDatabaseMetaData;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;

/**
 * Minimal but honest metadata. Tuple getters (tables, columns, keys...) return empty result sets;
 * schema validation through JDBC metadata is therefore not supported.
 */
public final class TursoDatabaseMetaData extends AbstractDatabaseMetaData {
  private static volatile String productVersion;
  private final TursoConnection connection;

  public TursoDatabaseMetaData(TursoConnection connection) {
    this.connection = connection;
  }

  @Override
  public Connection getConnection() {
    return connection;
  }

  @Override
  public String getURL() {
    return connection.jdbcUrl();
  }

  @Override
  public String getUserName() {
    return "";
  }

  @Override
  public String getDatabaseProductName() {
    return "SQLite";
  }

  @Override
  public String getDatabaseProductVersion() {
    return version();
  }

  @Override
  public int getDatabaseMajorVersion() {
    return major(version(), 3);
  }

  @Override
  public int getDatabaseMinorVersion() {
    return major(version().substring(Math.min(version().indexOf('.') + 1, version().length())), 47);
  }

  @Override
  public String getDriverName() {
    return "Turso JDBC Driver";
  }

  @Override
  public String getDriverVersion() {
    return TursoDriver.VERSION_MAJOR + "." + TursoDriver.VERSION_MINOR;
  }

  @Override
  public int getDriverMajorVersion() {
    return TursoDriver.VERSION_MAJOR;
  }

  @Override
  public int getDriverMinorVersion() {
    return TursoDriver.VERSION_MINOR;
  }

  @Override
  public int getJDBCMajorVersion() {
    return 4;
  }

  @Override
  public int getJDBCMinorVersion() {
    return 3;
  }

  @Override
  public String getIdentifierQuoteString() {
    return "\"";
  }

  @Override
  public String getSearchStringEscape() {
    return "\\";
  }

  @Override
  public String getExtraNameCharacters() {
    return "";
  }

  @Override
  public String getSQLKeywords() {
    return "";
  }

  @Override
  public String getNumericFunctions() {
    return "";
  }

  @Override
  public String getStringFunctions() {
    return "";
  }

  @Override
  public String getSystemFunctions() {
    return "";
  }

  @Override
  public String getTimeDateFunctions() {
    return "";
  }

  @Override
  public String getCatalogSeparator() {
    return ".";
  }

  @Override
  public String getCatalogTerm() {
    return "catalog";
  }

  @Override
  public String getSchemaTerm() {
    return "schema";
  }

  @Override
  public String getProcedureTerm() {
    return "procedure";
  }

  @Override
  public boolean supportsMixedCaseIdentifiers() {
    return true;
  }

  @Override
  public boolean storesUpperCaseIdentifiers() {
    return false;
  }

  @Override
  public boolean storesLowerCaseIdentifiers() {
    return false;
  }

  @Override
  public boolean storesMixedCaseIdentifiers() {
    return true;
  }

  @Override
  public boolean supportsMixedCaseQuotedIdentifiers() {
    return true;
  }

  @Override
  public boolean storesUpperCaseQuotedIdentifiers() {
    return false;
  }

  @Override
  public boolean storesLowerCaseQuotedIdentifiers() {
    return false;
  }

  @Override
  public boolean storesMixedCaseQuotedIdentifiers() {
    return true;
  }

  @Override
  public boolean isReadOnly() {
    return false;
  }

  @Override
  public boolean allProceduresAreCallable() {
    return false;
  }

  @Override
  public boolean allTablesAreSelectable() {
    return true;
  }

  @Override
  public boolean usesLocalFiles() {
    return false;
  }

  @Override
  public boolean usesLocalFilePerTable() {
    return false;
  }

  @Override
  public boolean nullsAreSortedHigh() {
    return false;
  }

  @Override
  public boolean nullsAreSortedLow() {
    return true;
  }

  @Override
  public boolean nullsAreSortedAtStart() {
    return true;
  }

  @Override
  public boolean nullsAreSortedAtEnd() {
    return false;
  }

  @Override
  public boolean supportsResultSetType(int type) {
    return type == ResultSet.TYPE_FORWARD_ONLY;
  }

  @Override
  public boolean supportsResultSetConcurrency(int type, int concurrency) {
    return type == ResultSet.TYPE_FORWARD_ONLY && concurrency == ResultSet.CONCUR_READ_ONLY;
  }

  @Override
  public boolean supportsResultSetHoldability(int holdability) {
    return holdability == ResultSet.HOLD_CURSORS_OVER_COMMIT;
  }

  @Override
  public int getResultSetHoldability() {
    return ResultSet.HOLD_CURSORS_OVER_COMMIT;
  }

  @Override
  public int getSQLStateType() {
    return DatabaseMetaData.sqlStateSQL;
  }

  @Override
  public boolean supportsTransactions() {
    return true;
  }

  @Override
  public boolean supportsTransactionIsolationLevel(int level) {
    return level == Connection.TRANSACTION_SERIALIZABLE
        || level == Connection.TRANSACTION_READ_COMMITTED
        || level == Connection.TRANSACTION_NONE;
  }

  @Override
  public int getDefaultTransactionIsolation() {
    return Connection.TRANSACTION_SERIALIZABLE;
  }

  @Override
  public boolean supportsBatchUpdates() {
    return true;
  }

  @Override
  public boolean supportsGetGeneratedKeys() {
    return true;
  }

  @Override
  public boolean generatedKeyAlwaysReturned() {
    return true;
  }

  @Override
  public boolean supportsSavepoints() {
    return true;
  }

  @Override
  public boolean supportsNamedParameters() {
    return false;
  }

  @Override
  public boolean supportsMultipleResultSets() {
    return false;
  }

  @Override
  public boolean supportsMultipleOpenResults() {
    return true;
  }

  @Override
  public boolean supportsMultipleTransactions() {
    return true;
  }

  @Override
  public boolean supportsCatalogsInDataManipulation() {
    return false;
  }

  @Override
  public boolean supportsCatalogsInProcedureCalls() {
    return false;
  }

  @Override
  public boolean supportsCatalogsInTableDefinitions() {
    return false;
  }

  @Override
  public boolean supportsCatalogsInIndexDefinitions() {
    return false;
  }

  @Override
  public boolean supportsCatalogsInPrivilegeDefinitions() {
    return false;
  }

  @Override
  public boolean supportsSchemasInDataManipulation() {
    return false;
  }

  @Override
  public boolean supportsSchemasInProcedureCalls() {
    return false;
  }

  @Override
  public boolean supportsSchemasInTableDefinitions() {
    return false;
  }

  @Override
  public boolean supportsSchemasInIndexDefinitions() {
    return false;
  }

  @Override
  public boolean supportsSchemasInPrivilegeDefinitions() {
    return false;
  }

  @Override
  public boolean isCatalogAtStart() {
    return true;
  }

  @Override
  public boolean supportsConvert() {
    return false;
  }

  @Override
  public boolean supportsConvert(int fromType, int toType) {
    return false;
  }

  @Override
  public boolean supportsStoredProcedures() {
    return false;
  }

  @Override
  public boolean supportsStoredFunctionsUsingCallSyntax() {
    return false;
  }

  @Override
  public boolean supportsCorrelatedSubqueries() {
    return true;
  }

  @Override
  public boolean supportsColumnAliasing() {
    return true;
  }

  @Override
  public boolean supportsExpressionsInOrderBy() {
    return true;
  }

  @Override
  public boolean supportsOrderByUnrelated() {
    return true;
  }

  @Override
  public boolean supportsGroupBy() {
    return true;
  }

  @Override
  public boolean supportsGroupByUnrelated() {
    return true;
  }

  @Override
  public boolean supportsGroupByBeyondSelect() {
    return true;
  }

  @Override
  public boolean supportsLikeEscapeClause() {
    return true;
  }

  @Override
  public boolean supportsOuterJoins() {
    return true;
  }

  @Override
  public boolean supportsFullOuterJoins() {
    return true;
  }

  @Override
  public boolean supportsLimitedOuterJoins() {
    return true;
  }

  @Override
  public boolean supportsNonNullableColumns() {
    return true;
  }

  @Override
  public boolean supportsMinimumSQLGrammar() {
    return true;
  }

  @Override
  public boolean supportsANSI92EntryLevelSQL() {
    return true;
  }

  @Override
  public boolean supportsIntegrityEnhancementFacility() {
    return true;
  }

  @Override
  public boolean doesMaxRowSizeIncludeBlobs() {
    return false;
  }

  @Override
  public boolean dataDefinitionCausesTransactionCommit() {
    return false;
  }

  @Override
  public boolean dataDefinitionIgnoredInTransactions() {
    return false;
  }

  @Override
  public boolean locatorsUpdateCopy() {
    return false;
  }

  @Override
  public boolean supportsStatementPooling() {
    return false;
  }

  @Override
  public boolean supportsSharding() {
    return false;
  }

  @Override
  public RowIdLifetime getRowIdLifetime() {
    return RowIdLifetime.ROWID_UNSUPPORTED;
  }

  @Override
  public int getMaxConnections() {
    return 0;
  }

  @Override
  public int getMaxStatementLength() {
    return 0;
  }

  @Override
  public int getMaxStatements() {
    return 0;
  }

  @Override
  public int getMaxRowSize() {
    return 0;
  }

  @Override
  public long getMaxLogicalLobSize() {
    return 0;
  }

  @Override
  public int getMaxColumnsInTable() {
    return 0;
  }

  @Override
  public int getMaxColumnsInSelect() {
    return 0;
  }

  @Override
  public int getMaxTableNameLength() {
    return 0;
  }

  @Override
  public int getMaxColumnNameLength() {
    return 0;
  }

  @Override
  public int getMaxSchemaNameLength() {
    return 0;
  }

  @Override
  public int getMaxCatalogNameLength() {
    return 0;
  }

  @Override
  public int getMaxUserNameLength() {
    return 0;
  }

  // ---------------------------------------------------------------- tuple getters

  @Override
  public ResultSet getTables(String catalog, String schemaPattern, String tableNamePattern,
      String[] types) {
    return empty();
  }

  @Override
  public ResultSet getColumns(String catalog, String schemaPattern, String tableNamePattern,
      String columnNamePattern) {
    return empty();
  }

  @Override
  public ResultSet getPrimaryKeys(String catalog, String schema, String table) {
    return empty();
  }

  @Override
  public ResultSet getImportedKeys(String catalog, String schema, String table) {
    return empty();
  }

  @Override
  public ResultSet getExportedKeys(String catalog, String schema, String table) {
    return empty();
  }

  @Override
  public ResultSet getCrossReference(String parentCatalog, String parentSchema, String parentTable,
      String foreignCatalog, String foreignSchema, String foreignTable) {
    return empty();
  }

  @Override
  public ResultSet getIndexInfo(String catalog, String schema, String table, boolean unique,
      boolean approximate) {
    return empty();
  }

  @Override
  public ResultSet getTypeInfo() {
    return empty();
  }

  @Override
  public ResultSet getSchemas() {
    return empty();
  }

  @Override
  public ResultSet getSchemas(String catalog, String schemaPattern) {
    return empty();
  }

  @Override
  public ResultSet getCatalogs() {
    return empty();
  }

  @Override
  public ResultSet getTableTypes() {
    return empty();
  }

  @Override
  public ResultSet getProcedures(String catalog, String schemaPattern, String procedureNamePattern) {
    return empty();
  }

  @Override
  public ResultSet getProcedureColumns(String catalog, String schemaPattern,
      String procedureNamePattern, String columnNamePattern) {
    return empty();
  }

  @Override
  public ResultSet getFunctions(String catalog, String schemaPattern, String functionNamePattern) {
    return empty();
  }

  @Override
  public ResultSet getFunctionColumns(String catalog, String schemaPattern,
      String functionNamePattern, String columnNamePattern) {
    return empty();
  }

  @Override
  public ResultSet getUDTs(String catalog, String schemaPattern, String typeNamePattern, int[] types) {
    return empty();
  }

  @Override
  public ResultSet getClientInfoProperties() {
    return empty();
  }

  @Override
  public ResultSet getPseudoColumns(String catalog, String schemaPattern, String tableNamePattern,
      String columnNamePattern) {
    return empty();
  }

  @Override
  public ResultSet getBestRowIdentifier(String catalog, String schema, String table, int scope,
      boolean nullable) {
    return empty();
  }

  @Override
  public ResultSet getVersionColumns(String catalog, String schema, String table) {
    return empty();
  }

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    if (iface.isInstance(this)) {
      return iface.cast(this);
    }
    throw new java.sql.SQLFeatureNotSupportedException("Not a wrapper for " + iface.getName());
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) {
    return iface.isInstance(this);
  }

  private ResultSet empty() {
    return TursoResultSet.empty(connection, null);
  }

  private String version() {
    String value = productVersion;
    if (value == null) {
      try {
        value = connection.dbVersion();
      } catch (SQLException e) {
        value = null;
      }
      if (value == null || value.isBlank()) {
        value = "3.47.0";
      }
      productVersion = value;
    }
    return value;
  }

  private static int major(String value, int fallback) {
    int dot = value.indexOf('.');
    String head = dot < 0 ? value : value.substring(0, dot);
    try {
      return Integer.parseInt(head.trim());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }
}

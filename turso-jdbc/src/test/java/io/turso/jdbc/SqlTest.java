package io.turso.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SqlTest {

  @Test
  void findsFirstKeywordSkippingComments() {
    assertEquals("SELECT", Sql.firstKeyword("  -- hello\n /* x */ SELECT 1"));
    assertEquals("INSERT", Sql.firstKeyword("insert into t values (1)"));
    assertNull(Sql.firstKeyword("  -- only a comment"));
    assertNull(Sql.firstKeyword(""));
  }

  @Test
  void detectsTransactionControl() {
    assertTrue(Sql.isTransactionControl("BEGIN IMMEDIATE"));
    assertTrue(Sql.isTransactionControl("rollback to savepoint x"));
    assertTrue(Sql.isTransactionControl("SAVEPOINT a"));
    assertFalse(Sql.isTransactionControl("SELECT begin"));
  }

  @Test
  void quotesOnlyUnsafeIdentifiers() {
    assertEquals("abc_1", Sql.quoteIdentifier("abc_1"));
    assertEquals("\"a b\"", Sql.quoteIdentifier("a b"));
    assertEquals("\"a\"\"b\"", Sql.quoteIdentifier("a\"b"));
  }
}

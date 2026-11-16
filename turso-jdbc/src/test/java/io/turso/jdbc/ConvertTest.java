package io.turso.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class ConvertTest {

  @Test
  void parsesTimestampsInCommonSqliteFormats() throws Exception {
    assertEquals(Timestamp.valueOf("2024-01-02 03:04:05.123"),
        Convert.timestamp("2024-01-02 03:04:05.123"));
    assertEquals(Timestamp.valueOf("2024-01-02 03:04:05"),
        Convert.timestamp("2024-01-02T03:04:05"));
    assertEquals(Timestamp.valueOf("2024-01-02 00:00:00"),
        Convert.timestamp("2024-01-02"));
    assertEquals(new Timestamp(0L), Convert.timestamp(0L));
  }

  @Test
  void convertsTypedDateTimes() throws Exception {
    LocalDateTime value = LocalDateTime.of(2024, 1, 2, 3, 4, 5);
    assertEquals(LocalDate.of(2024, 1, 2), Convert.localDate(value));
    assertEquals(LocalTime.of(3, 4, 5), Convert.localTime(value));
    assertEquals(value, Convert.localDateTime("2024-01-02 03:04:05"));
  }

  @Test
  void convertsBooleansAndNumbers() throws Exception {
    assertTrue(Convert.boolVal(1L));
    assertTrue(Convert.boolVal("true"));
    assertTrue(!Convert.boolVal(0L));
    assertEquals(42L, Convert.longVal("42"));
    assertEquals(1L, Convert.longVal(1.9d));
    assertEquals(new BigDecimal("1.25"), Convert.bigDecimal("1.25"));
    assertNull(Convert.str(null));
  }

  @Test
  void formatsParametersForStorage() throws Exception {
    assertEquals("2024-01-02 03:04:05.000",
        Convert.toParam(Timestamp.valueOf("2024-01-02 03:04:05")));
    assertEquals("2024-01-02", Convert.toParam(LocalDate.of(2024, 1, 2)));
    assertEquals("03:04:05.000", Convert.toParam(LocalTime.of(3, 4, 5)));
    assertEquals(7, ((Number) Convert.toParam(7)).intValue());
  }
}

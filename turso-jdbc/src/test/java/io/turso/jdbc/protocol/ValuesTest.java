package io.turso.jdbc.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ValuesTest {

  private static Object roundTrip(Object value) {
    String json = Json.write(w -> Values.encode(w, value));
    return Values.decode(Json.parse(json));
  }

  @Test
  void encodesIntegersAsDecimalStrings() {
    String json = Json.write(w -> Values.encode(w, 42L));
    assertTrue(json.contains("\"type\":\"integer\""));
    assertTrue(json.contains("\"value\":\"42\""));
    assertEquals(42L, roundTrip(42L));
  }

  @Test
  void keepsInt64Range() {
    assertEquals(Long.MIN_VALUE, roundTrip(Long.MIN_VALUE));
    assertEquals(Long.MAX_VALUE, roundTrip(Long.MAX_VALUE));
  }

  @Test
  void encodesBooleansAsInteger() {
    String json = Json.write(w -> Values.encode(w, true));
    assertTrue(json.contains("\"value\":\"1\""));
    assertEquals(1L, Values.decode(Json.parse(json)));
  }

  @Test
  void encodesBlobsUnpaddedAndDecodesThem() {
    String json = Json.write(w -> Values.encode(w, "hello".getBytes()));
    assertTrue(json.contains("\"base64\":\"aGVsbG8\""));
    assertArrayEquals("hello".getBytes(), (byte[]) Values.decode(Json.parse(json)));
  }

  @Test
  void decodesPaddedAndUnpaddedBase64() {
    assertArrayEquals("hi".getBytes(), Values.decodeBlob("aGk="));
    assertArrayEquals("hi".getBytes(), Values.decodeBlob("aGk"));
    assertArrayEquals(new byte[0], Values.decodeBlob(null));
  }

  @Test
  void decodesNullAndMissingFloatAsNan() {
    assertNull(Values.decode(Json.parse("{\"type\":\"null\"}")));
    assertEquals(Double.NaN, Values.decode(Json.parse("{\"type\":\"float\",\"value\":null}")));
  }

  @Test
  void encodesNamedArguments() {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("a", 1L);
    String json = Json.write(w -> Values.encodeNamed(w, "a", 1L));
    assertTrue(json.contains("\"name\":\"a\""));
    assertTrue(json.contains("\"type\":\"integer\""));
    assertEquals(1, args.size());
  }

  @Test
  void encodesBigDecimalAsText() {
    String json = Json.write(w -> Values.encode(w, new java.math.BigDecimal("1.2300")));
    assertTrue(json.contains("\"type\":\"text\""));
    assertTrue(json.contains("\"value\":\"1.2300\""));
    assertEquals("1.2300", Values.decode(Json.parse(json)));
  }

  @Test
  void base64HelperMatchesDecoder() {
    assertEquals(Base64.getEncoder().withoutPadding().encodeToString("x".getBytes()),
        Values.encodeBlob("x".getBytes()));
  }
}

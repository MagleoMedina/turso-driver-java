package io.turso.jdbc.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

  @Test
  void parsesNestedDocument() {
    Object parsed = Json.parse("{\"a\":[1,2.5,true,false,null,\"x\"],\"b\":{\"c\":\"d\"}}");
    Map<String, Object> object = Json.object(parsed);
    List<Object> array = Json.array(object.get("a"));
    assertEquals(1L, array.get(0));
    assertEquals(2.5d, array.get(1));
    assertEquals(Boolean.TRUE, array.get(2));
    assertNull(array.get(4));
    assertEquals("x", array.get(5));
    assertEquals("d", Json.object(object.get("b")).get("c"));
  }

  @Test
  void parsesEscapesAndUnicode() {
    assertEquals("a\"b\\c\n\t\u00e9", Json.parse("\"a\\\"b\\\\c\\n\\t\\u00e9\""));
  }

  @Test
  void parsesInt64PrecisionAsString() {
    Map<String, Object> object = Json.object(Json.parse("{\"v\":\"-9223372036854775808\"}"));
    assertEquals("-9223372036854775808", object.get("v"));
  }

  @Test
  void writesAndRoundTrips() {
    String json = Json.write(w -> w.beginObject()
        .name("baton").value("abc")
        .name("requests").beginArray()
        .beginObject().name("type").value("execute").endObject()
        .endArray()
        .name("n").value(42)
        .name("b").value(true)
        .name("nil").value(null)
        .endObject());
    Map<String, Object> parsed = Json.object(Json.parse(json));
    assertEquals("abc", parsed.get("baton"));
    assertEquals(42L, parsed.get("n"));
    assertEquals(Boolean.TRUE, parsed.get("b"));
    assertNull(parsed.get("nil"));
    assertEquals(1, Json.array(parsed.get("requests")).size());
  }

  @Test
  void rejectsTrailingGarbage() {
    assertThrows(IllegalArgumentException.class, () -> Json.parse("{} x"));
  }

  @Test
  void rejectsNonFiniteNumbers() {
    assertThrows(IllegalArgumentException.class,
        () -> Json.write(w -> w.value(Double.NaN)));
  }

  @Test
  void objectValuesAreCastable() {
    assertInstanceOf(Map.class, Json.parse("{}"));
    assertInstanceOf(List.class, Json.parse("[]"));
  }
}

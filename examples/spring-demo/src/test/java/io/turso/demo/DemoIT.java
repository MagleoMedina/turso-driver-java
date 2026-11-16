package io.turso.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.turso.demo.note.Note;
import io.turso.demo.note.NoteService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

@Tag("integration")
@SpringBootTest(properties = "demo.run=false")
@EnabledIfEnvironmentVariable(named = "TURSO_TOKEN", matches = ".+")
class DemoIT {

  @Autowired
  private NoteService notes;

  @Autowired
  private JdbcClient jdbc;

  @BeforeEach
  void clean() {
    jdbc.sql("DELETE FROM it_demo_note").update();
  }

  @Test
  void jpaIdentityAndJdbcClient() {
    Note note = notes.create("first", "from JPA");
    assertNotNull(note.getId());
    assertEquals(1, notes.count());
    assertEquals(List.of("first"), notes.titles());
  }

  @Test
  void transactionRollsBack() {
    assertThrows(IllegalStateException.class, () -> notes.createAndFail("nope"));
    assertEquals(0, notes.count());
  }

  @Test
  void transactionalBatch() {
    notes.createMany(List.of("a", "b", "c"));
    assertEquals(3, notes.count());
  }
}

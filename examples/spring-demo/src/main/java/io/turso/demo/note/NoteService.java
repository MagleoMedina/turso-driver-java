package io.turso.demo.note;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NoteService {

  private final NoteRepository repository;
  private final JdbcClient jdbc;

  public NoteService(NoteRepository repository, JdbcClient jdbc) {
    this.repository = repository;
    this.jdbc = jdbc;
  }

  @Transactional
  public Note create(String title, String body) {
    return repository.save(new Note(title, body));
  }

  @Transactional
  public void createAndFail(String title) {
    repository.save(new Note(title, null));
    throw new IllegalStateException("boom");
  }

  @Transactional
  public void createMany(List<String> titles) {
    for (String title : titles) {
      repository.save(new Note(title, null));
    }
  }

  public List<String> titles() {
    return jdbc.sql("SELECT title FROM it_demo_note ORDER BY id")
        .query(String.class)
        .list();
  }

  public int count() {
    return jdbc.sql("SELECT count(*) FROM it_demo_note")
        .query(Integer.class)
        .single();
  }
}

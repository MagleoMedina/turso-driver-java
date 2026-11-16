package io.turso.demo;

import io.turso.demo.note.NoteService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class DemoApplication {

  public static void main(String[] args) {
    SpringApplication.run(DemoApplication.class, args);
  }

  @Bean
  @ConditionalOnProperty(name = "demo.run", havingValue = "true", matchIfMissing = true)
  CommandLineRunner demoRunner(NoteService notes) {
    return args -> {
      var note = notes.create("hello from Spring Boot", "Turso + JPA");
      System.out.println("Inserted note id=" + note.getId() + ", rows=" + notes.count());
      System.out.println("Titles: " + notes.titles());
    };
  }
}

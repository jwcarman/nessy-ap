/*
 * Copyright © ${year} James Carman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jwcarman.nessyap.agent.questions;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessyap.agent.cases.CaseInput;
import org.jwcarman.nessyap.agent.cases.CaseRecord;
import org.jwcarman.nessyap.agent.cases.CaseTimeline;
import org.jwcarman.nessyap.agent.cases.Cases;
import org.jwcarman.nessyap.agent.support.Ids;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Questions for the people inside the company. A question waits for one person, who answers it on
 * the workbench once. The answer is that person's own word: it reaches the case's agent as a
 * trusted input, in the same transaction that records it.
 */
@Component
public class Questions {

  static final int MAX_TEXT = 1_000;
  static final int MAX_CHOICES = 4;
  static final int MAX_CHOICE = 80;
  static final int MAX_PER_CASE = 3;

  /** What became of an answer. */
  public sealed interface Answered {
    record Told(Question question) implements Answered {}

    record NotYours() implements Answered {}

    record AlreadyAnswered() implements Answered {}

    record NotAChoice() implements Answered {}

    record NeedsAnAnswer() implements Answered {}

    record NoSuchQuestion() implements Answered {}
  }

  private final JdbcClient jdbc;
  private final Cases cases;
  private final CaseTimeline timeline;
  private final QueuedHarness<CaseInput> agent;
  private final Clock clock;

  public Questions(
      JdbcClient jdbc,
      Cases cases,
      CaseTimeline timeline,
      QueuedHarness<CaseInput> agent,
      Clock clock) {
    this.jdbc = jdbc;
    this.cases = cases;
    this.timeline = timeline;
    this.agent = agent;
    this.clock = clock;
  }

  /**
   * Asks one person a question about a case.
   *
   * @throws IllegalArgumentException when the question or a choice is too long, or there are too
   *     many choices
   * @throws IllegalStateException when a question already waits on the case, or the case has had as
   *     many questions as it may
   */
  @Transactional
  public Question ask(UUID exceptionId, String askedOf, String text, List<String> choices) {
    List<String> offered = choices == null ? List.of() : List.copyOf(choices);
    if (text == null || text.isBlank() || text.length() > MAX_TEXT) {
      throw new IllegalArgumentException(
          "A question needs words, and at most " + MAX_TEXT + " characters.");
    }
    if (offered.size() > MAX_CHOICES
        || offered.stream().anyMatch(c -> c == null || c.isBlank() || c.length() > MAX_CHOICE)) {
      throw new IllegalArgumentException(
          "Offer at most " + MAX_CHOICES + " choices, each at most " + MAX_CHOICE + " characters.");
    }
    long waiting = count(exceptionId, "and answered_at is null");
    if (waiting > 0) {
      throw new IllegalStateException(
          "A question already waits on this case. Wait for its answer before asking another.");
    }
    if (count(exceptionId, "") >= MAX_PER_CASE) {
      throw new IllegalStateException(
          "This case has had " + MAX_PER_CASE + " questions, the most allowed. Hold and note it.");
    }
    Question question =
        new Question(
            Ids.next(),
            exceptionId,
            askedOf,
            text,
            offered,
            clock.instant(),
            null,
            null,
            null,
            null);
    jdbc.sql(
            """
            insert into question (id, exception_id, asked_of, text, choices, asked_at)
            values (:id, :case, :askedOf, :text, :choices, :at)
            """)
        .param("id", question.id())
        .param("case", exceptionId)
        .param("askedOf", askedOf)
        .param("text", text)
        .param("choices", offered.toArray(String[]::new))
        .param("at", Timestamp.from(question.askedAt()))
        .update();
    timeline.record(exceptionId, "question-asked", "to " + askedOf + ": " + text);
    return question;
  }

  /** Answers a question as one person. Only the person asked may answer, and only once. */
  @Transactional
  public Answered answer(UUID questionId, String person, String choice, String comment) {
    Optional<Question> found =
        jdbc.sql("select * from question where id = :id for update")
            .param("id", questionId)
            .query(Questions::question)
            .optional();
    if (found.isEmpty()) {
      return new Answered.NoSuchQuestion();
    }
    Question question = found.get();
    if (!question.askedOf().equals(person)) {
      return new Answered.NotYours();
    }
    if (question.answered()) {
      return new Answered.AlreadyAnswered();
    }
    String picked = choice == null || choice.isBlank() ? null : choice;
    String words = comment == null || comment.isBlank() ? null : comment.strip();
    if (picked != null && !question.choices().contains(picked)) {
      return new Answered.NotAChoice();
    }
    if (picked == null && words == null) {
      return new Answered.NeedsAnAnswer();
    }
    Instant now = clock.instant();
    jdbc.sql(
            """
            update question set answered_by = :person, choice = :choice, comment = :comment,
                answered_at = :at
            where id = :id
            """)
        .param("person", person)
        .param("choice", picked)
        .param("comment", words)
        .param("at", Timestamp.from(now))
        .param("id", questionId)
        .update();
    timeline.record(
        question.exceptionId(),
        "answer",
        person
            + " answered: "
            + (picked == null ? words : picked + (words == null ? "" : ". " + words)));
    cases
        .find(question.exceptionId())
        .map(CaseRecord::agentId)
        .ifPresent(
            agentId ->
                agent.tell(
                    agentId, new CaseInput.PersonAnswered(person, question.text(), picked, words)));
    return new Answered.Told(
        new Question(
            question.id(),
            question.exceptionId(),
            person,
            question.text(),
            question.choices(),
            question.askedAt(),
            person,
            picked,
            words,
            now));
  }

  public List<Question> forCase(UUID exceptionId) {
    return jdbc.sql("select * from question where exception_id = :case order by asked_at")
        .param("case", exceptionId)
        .query(Questions::question)
        .list();
  }

  /** The questions that wait for one person, oldest first. */
  public List<Question> waitingFor(String person) {
    return jdbc.sql(
            "select * from question where asked_of = :person and answered_at is null"
                + " order by asked_at")
        .param("person", person)
        .query(Questions::question)
        .list();
  }

  private long count(UUID exceptionId, String andWhere) {
    return jdbc.sql("select count(*) from question where exception_id = :case " + andWhere)
        .param("case", exceptionId)
        .query(Long.class)
        .single();
  }

  private static Question question(ResultSet rs, int row) throws SQLException {
    Array choices = rs.getArray("choices");
    Timestamp answeredAt = rs.getTimestamp("answered_at");
    return new Question(
        rs.getObject("id", UUID.class),
        rs.getObject("exception_id", UUID.class),
        rs.getString("asked_of"),
        rs.getString("text"),
        Arrays.asList((String[]) choices.getArray()),
        rs.getTimestamp("asked_at").toInstant(),
        rs.getString("answered_by"),
        rs.getString("choice"),
        rs.getString("comment"),
        answeredAt == null ? null : answeredAt.toInstant());
  }
}

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
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.jwcarman.nessyap.agent.cases.CaseTimeline;
import org.jwcarman.nessyap.agent.support.Ids;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Questions for the people inside the company. A question waits for one person, who answers it on
 * the workbench once ({@link Answers}).
 */
@Component
public class Questions {

  static final int MAX_TEXT = 1_000;
  static final int MAX_CHOICES = 4;
  static final int MAX_CHOICE = 80;
  static final int MAX_PER_CASE = 3;

  private final JdbcClient jdbc;
  private final CaseTimeline timeline;
  private final Clock clock;

  public Questions(JdbcClient jdbc, CaseTimeline timeline, Clock clock) {
    this.jdbc = jdbc;
    this.timeline = timeline;
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
    if (choices != null && choices.stream().anyMatch(c -> c == null || c.isBlank())) {
      throw new IllegalArgumentException("A choice needs words.");
    }
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
    try {
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
    } catch (DuplicateKeyException askedAtOnce) {
      // Another question was asked on this case at the same moment, and it waits now.
      throw new IllegalStateException(
          "A question already waits on this case. Wait for its answer before asking another.",
          askedAtOnce);
    }
    timeline.record(exceptionId, "question-asked", "to " + askedOf + ": " + text);
    return question;
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

  static Question question(ResultSet rs, int row) throws SQLException {
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

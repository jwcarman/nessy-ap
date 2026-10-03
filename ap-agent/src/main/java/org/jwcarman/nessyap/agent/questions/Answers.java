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

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessyap.agent.cases.CaseInput;
import org.jwcarman.nessyap.agent.cases.CaseRecord;
import org.jwcarman.nessyap.agent.cases.CaseStatus;
import org.jwcarman.nessyap.agent.cases.CaseTimeline;
import org.jwcarman.nessyap.agent.cases.Cases;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * A person's answers to the agent's questions. The answer is that person's own word, given signed
 * in: it reaches the case's agent as a trusted input, in the same transaction that records it.
 */
@Component
public class Answers {

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

  public Answers(
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
    cases.moveStatus(question.exceptionId(), CaseStatus.AWAITING_ANSWER, CaseStatus.INVESTIGATING);
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
            question.askedOf(),
            question.text(),
            question.choices(),
            question.askedAt(),
            person,
            picked,
            words,
            now));
  }
}

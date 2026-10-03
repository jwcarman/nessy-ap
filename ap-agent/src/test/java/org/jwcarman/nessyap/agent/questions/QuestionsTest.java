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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessyap.agent.ApAgentIntegrationTest;
import org.jwcarman.nessyap.agent.cases.CaseTimeline;
import org.springframework.beans.factory.annotation.Autowired;

/** A question waits for one person, who answers it once; the answer reaches the case's agent. */
class QuestionsTest extends ApAgentIntegrationTest {

  @Autowired Questions questions;
  @Autowired CaseTimeline timeline;

  @Test
  void the_person_asked_answers_once_and_the_answer_is_on_the_case() {
    UUID exceptionId = openCase();
    Question q =
        questions.ask(exceptionId, "bob", "Did you agree 11.60?", List.of("Agreed", "Not agreed"));

    Questions.Answered first = questions.answer(q.id(), "bob", "Agreed", null);
    Questions.Answered second = questions.answer(q.id(), "bob", "Not agreed", null);

    assertThat(first).isInstanceOf(Questions.Answered.Told.class);
    assertThat(second).isInstanceOf(Questions.Answered.AlreadyAnswered.class);
    assertThat(questions.forCase(exceptionId))
        .singleElement()
        .satisfies(
            answered -> {
              assertThat(answered.answeredBy()).isEqualTo("bob");
              assertThat(answered.choice()).isEqualTo("Agreed");
            });
    assertThat(timeline.of(exceptionId))
        .extracting(CaseTimeline.CaseEvent::kind)
        .containsOnlyOnce("answer");
  }

  @Test
  void nobody_but_the_person_asked_may_answer() {
    Question q = questions.ask(openCase(), "bob", "Which order?", List.of());

    assertThat(questions.answer(q.id(), "betty", null, "PO-7"))
        .isInstanceOf(Questions.Answered.NotYours.class);
    assertThat(questions.forCase(q.exceptionId())).singleElement().matches(w -> !w.answered());
  }

  @Test
  void a_choice_must_be_one_offered_and_an_open_question_needs_words() {
    Question choices = questions.ask(openCase(), "bob", "Agreed?", List.of("Yes", "No"));
    Question open = questions.ask(openCase(), "bob", "Which order?", List.of());

    assertThat(questions.answer(choices.id(), "bob", "Maybe", null))
        .isInstanceOf(Questions.Answered.NotAChoice.class);
    assertThat(questions.answer(open.id(), "bob", null, " "))
        .isInstanceOf(Questions.Answered.NeedsAnAnswer.class);
    assertThat(questions.answer(UUID.randomUUID(), "bob", null, "x"))
        .isInstanceOf(Questions.Answered.NoSuchQuestion.class);
  }

  @Test
  void one_question_waits_per_case_and_three_is_the_most() {
    UUID exceptionId = openCase();
    Question first = questions.ask(exceptionId, "bob", "One?", List.of());

    assertThatThrownBy(() -> questions.ask(exceptionId, "bob", "Two?", List.of()))
        .isInstanceOf(IllegalStateException.class);
    questions.answer(first.id(), "bob", null, "yes");
    Question second = questions.ask(exceptionId, "bob", "Two?", List.of());
    questions.answer(second.id(), "bob", null, "yes");
    Question third = questions.ask(exceptionId, "bob", "Three?", List.of());
    questions.answer(third.id(), "bob", null, "yes");
    assertThatThrownBy(() -> questions.ask(exceptionId, "bob", "Four?", List.of()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void a_question_stays_short_and_so_do_its_choices() {
    UUID exceptionId = openCase();
    String tooLong = "x".repeat(1001);
    List<String> fiveChoices = List.of("a", "b", "c", "d", "e");

    assertThatThrownBy(() -> questions.ask(exceptionId, "bob", tooLong, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> questions.ask(exceptionId, "bob", "Which?", fiveChoices))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void a_person_sees_only_the_questions_that_wait_for_them() {
    Question mine = questions.ask(openCase(), "bob", "Mine?", List.of());
    questions.ask(openCase(), "betty", "Hers?", List.of());

    assertThat(questions.waitingFor("bob")).extracting(Question::id).contains(mine.id());
    assertThat(questions.waitingFor("bob")).extracting(Question::askedOf).containsOnly("bob");
  }
}

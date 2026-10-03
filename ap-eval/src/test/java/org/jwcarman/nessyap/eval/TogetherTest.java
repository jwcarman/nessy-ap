/*
 * Copyright © 2026 James Carman
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
package org.jwcarman.nessyap.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Runs made side by side come back in order, and one that breaks does not take the rest. */
class TogetherTest {

  @Test
  void a_run_that_throws_is_scored_as_failed_and_the_others_are_kept() {
    Schedule schedule =
        Schedule.of(List.of(Scenarios.named("no-po"), Scenarios.named("duplicate")), 1);

    List<RunScore> scores =
        ApEvalApplication.together(
            (scenario, repetition) -> {
              if (scenario.name().equals("no-po")) {
                throw new IllegalStateException("the desk went away");
              }
              return Scoring.score(scenario, repetition, Runner.unobserved("RESOLVED"));
            },
            schedule.together(),
            2);

    assertThat(scores).extracting(RunScore::scenario).containsExactly("no-po", "duplicate");
    assertThat(scores.getFirst().caseStatus()).isEqualTo("ERROR");
    assertThat(scores.getFirst().passed()).isFalse();
  }
}

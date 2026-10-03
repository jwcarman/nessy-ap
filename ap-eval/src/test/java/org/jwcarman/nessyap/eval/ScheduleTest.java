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
package org.jwcarman.nessyap.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Cases run together, except one whose faults would break the cases beside it. */
class ScheduleTest {

  @Test
  void a_scenario_that_breaks_the_erp_runs_alone_after_the_rest() {
    Schedule schedule =
        Schedule.of(
            List.of(
                Scenarios.named("price-variance-small"),
                Scenarios.named("flaky-erp"),
                Scenarios.named("no-po"),
                Scenarios.named("slow-erp")),
            2);

    assertThat(schedule.together())
        .extracting(r -> r.scenario().name() + "#" + r.repetition())
        .containsExactly("price-variance-small#1", "price-variance-small#2", "no-po#1", "no-po#2");
    assertThat(schedule.alone())
        .extracting(r -> r.scenario().name() + "#" + r.repetition())
        .containsExactly("flaky-erp#1", "flaky-erp#2", "slow-erp#1", "slow-erp#2");
  }
}

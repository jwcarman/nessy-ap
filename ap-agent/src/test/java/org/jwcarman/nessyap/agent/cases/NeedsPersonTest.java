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
package org.jwcarman.nessyap.agent.cases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.jwcarman.nessyap.agent.ScriptedProvider.call;
import static org.jwcarman.nessyap.agent.ScriptedProvider.steps;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessyap.agent.ApAgentIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A case whose agent ends a turn with nothing in motion goes in front of a person. Measured: the
 * agent sometimes ended a turn having only read or only noted, and a dropped model call ended turns
 * too; either way the case sat "investigating" with nobody acting on it.
 */
class NeedsPersonTest extends ApAgentIntegrationTest {

  @Autowired QueuedHarness<CaseInput> agent;

  @Test
  void a_turn_that_ends_with_nothing_in_motion_puts_the_case_in_front_of_a_person() {
    UUID exceptionId = openCase();
    AgentId agentId = caseIndex.agentFor(exceptionId);

    agent.tell(agentId, new CaseInput.PersonNote("clara", "look at this"));

    await()
        .atMost(Duration.ofSeconds(20))
        .until(() -> caseIndex.find(exceptionId).orElseThrow().status() == CaseStatus.NEEDS_PERSON);
    assertThat(narration.count(agentId, Narration.TurnEnded.class)).isEqualTo(1);
  }

  @Test
  void a_new_turn_takes_the_case_back_from_a_person() {
    UUID exceptionId = openCase();
    AgentId agentId = caseIndex.agentFor(exceptionId);
    caseIndex.setStatus(exceptionId, CaseStatus.NEEDS_PERSON);
    model.script(
        steps(
            call(
                "c1",
                "propose_resolution",
                "{\"action\":\"hold\",\"rationale\":\"Goods arrived; checking.\",\"evidence\":[]}")));

    agent.tell(agentId, new CaseInput.PersonNote("clara", "the goods arrived"));

    await()
        .atMost(Duration.ofSeconds(20))
        .until(
            () ->
                caseIndex.find(exceptionId).orElseThrow().status() == CaseStatus.AWAITING_DECISION);
  }

  @Test
  void a_turn_that_ends_with_nothing_in_motion_says_so_on_the_timeline() {
    UUID exceptionId = openCase();

    agent.tell(caseIndex.agentFor(exceptionId), new CaseInput.PersonNote("clara", "look"));

    await()
        .atMost(Duration.ofSeconds(20))
        .until(
            () -> timeline.of(exceptionId).stream().anyMatch(e -> e.kind().equals("needs-person")));
  }

  @Autowired CaseTimeline timeline;
}

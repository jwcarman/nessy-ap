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
package org.jwcarman.nessyap.agent.oversight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.UsageReports;
import org.jwcarman.nessy.engine.store.TurnHistories;
import org.jwcarman.nessyap.agent.ApAgentIntegrationTest;
import org.jwcarman.nessyap.agent.cases.CaseInput;
import org.jwcarman.nessyap.agent.cases.CaseStatus;
import org.jwcarman.nessyap.agent.cases.CaseTimeline;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** People oversee the agents at one door: a pause holds every input, a spent budget holds one. */
class OversightTest extends ApAgentIntegrationTest {

  private static final Duration PATIENCE = Duration.ofSeconds(20);

  @Autowired GuardedAgents agents;
  @Autowired OversightController oversight;
  @Autowired CaseTimeline timeline;
  @Autowired Switches switches;
  @Autowired TurnHistories histories;
  @Autowired UsageReports reports;
  @Autowired TransactionTemplate tx;
  @Autowired JsonMapper json;
  @Autowired Clock clock;

  @Autowired
  @Qualifier("apAgentHarness")
  QueuedHarness<CaseInput> unguarded;

  @AfterEach
  void resumed() {
    if (agents.paused()) {
      agents.resume("test");
    }
  }

  private long turns(AgentId agentId) {
    return narration.count(agentId, Narration.TurnStarted.class);
  }

  private static TestingAuthenticationToken as(String user, String role) {
    return new TestingAuthenticationToken(
        user, "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + role)));
  }

  @Test
  void while_the_agents_are_paused_an_input_is_held_and_its_case_waits_for_a_person() {
    UUID exceptionId = openCase();
    AgentId agentId = caseIndex.agentFor(exceptionId);
    oversight.pause(as("connie", "controller"));

    agents.tell(agentId, new CaseInput.PersonNote("clara", "look at this"));

    assertThat(caseIndex.find(exceptionId).orElseThrow().status())
        .isEqualTo(CaseStatus.NEEDS_PERSON);
    assertThat(timeline.of(exceptionId)).extracting(CaseTimeline.CaseEvent::kind).contains("held");
    await().during(Duration.ofSeconds(2)).atMost(PATIENCE).until(() -> turns(agentId) == 0);
  }

  @Test
  void resuming_tells_each_held_input_to_its_agent() {
    UUID exceptionId = openCase();
    AgentId agentId = caseIndex.agentFor(exceptionId);
    oversight.pause(as("connie", "controller"));
    agents.tell(agentId, new CaseInput.PersonNote("clara", "look at this"));

    OversightController.Changed changed = oversight.resume(as("connie", "controller"));

    assertThat(changed.agentsPaused()).isFalse();
    assertThat(changed.released()).isEqualTo(1);
    await().atMost(PATIENCE).until(() -> turns(agentId) == 1);
  }

  @Test
  void only_a_controller_may_pause_the_agents() {
    TestingAuthenticationToken clerk = as("clara", "ap-clerk");

    assertThat(oversight.state(clerk).agentsPaused()).isFalse();
    assertThatThrownBy(() -> oversight.pause(clerk)).isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void a_case_whose_agent_has_spent_its_budget_goes_to_a_person() {
    // A budget of one turn: the first input is told, the second is held.
    GuardedAgents oneTurn =
        new GuardedAgents(
            unguarded,
            switches,
            new AgentBudget(histories, reports, 1, 1_000_000),
            caseIndex,
            timeline,
            jdbc,
            json,
            clock,
            tx);
    UUID exceptionId = openCase();
    AgentId agentId = caseIndex.agentFor(exceptionId);
    oneTurn.tell(agentId, new CaseInput.PersonNote("clara", "first"));
    await().atMost(PATIENCE).until(() -> narration.count(agentId, Narration.TurnEnded.class) == 1);

    oneTurn.tell(agentId, new CaseInput.PersonNote("clara", "second"));

    assertThat(
            jdbc.sql("select count(*) from held_input where agent_id = :id and reason = 'budget'")
                .param("id", agentId.value())
                .query(Long.class)
                .single())
        .isEqualTo(1);
    assertThat(timeline.of(exceptionId))
        .extracting(CaseTimeline.CaseEvent::text)
        .anyMatch(text -> text.contains("spent its budget (1 turns"));
  }
}

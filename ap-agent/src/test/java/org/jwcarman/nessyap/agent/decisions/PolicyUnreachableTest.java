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
package org.jwcarman.nessyap.agent.decisions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.jwcarman.nessyap.agent.ScriptedProvider.call;
import static org.jwcarman.nessyap.agent.ScriptedProvider.steps;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessy.approval.policy.opa.OpaPolicyEngine;
import org.jwcarman.nessyap.agent.ApAgentIntegrationTest;
import org.jwcarman.nessyap.agent.cases.CaseInput;
import org.jwcarman.nessyap.agent.cases.Cases;
import org.jwcarman.nessyap.contracts.MatchExceptionRaised;
import org.jwcarman.nessyap.contracts.ReasonCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.json.JsonMapper;

/** Its own context: the policy engine here is nowhere. */
@Import(PolicyUnreachableTest.NowherePolicy.class)
class PolicyUnreachableTest extends ApAgentIntegrationTest {

  /** A policy engine at an address nothing listens on, preferred over the real one. */
  @TestConfiguration(proxyBeanMethods = false)
  static class NowherePolicy {

    @Bean
    @Primary
    OpaPolicyEngine nowhere(JsonMapper json) {
      return OpaPolicyEngine.of(
          config ->
              config.url("http://127.0.0.1:1").decisionPath("ap/decision").objectMapper(json));
    }
  }

  @Autowired QueuedHarness<CaseInput> agent;
  @Autowired Cases cases;
  @Autowired Decisions decisions;

  /**
   * An approver that cannot reach its policy has no answer to give: the call fails, the model is
   * told, and nothing is approved or left waiting on a person.
   */
  @Test
  void a_proposal_made_while_the_policy_is_unreachable_fails_and_is_never_approved() {
    model.script(
        steps(call("c1", "propose_resolution", "{\"action\":\"hold\",\"rationale\":\"r\"}")));
    UUID exceptionId = UUID.randomUUID();
    MatchExceptionRaised raised =
        new MatchExceptionRaised(
            UUID.randomUUID(),
            Instant.now(),
            exceptionId,
            UUID.randomUUID(),
            "INV-1",
            UUID.randomUUID(),
            "PO-1",
            ReasonCode.NO_RECEIPT,
            "s",
            BigDecimal.TEN);
    cases.open(raised);
    AgentId agentId = cases.agentFor(exceptionId);

    agent.tell(agentId, new CaseInput.ExceptionRaised(raised));

    await()
        .atMost(Duration.ofSeconds(30))
        .until(() -> narration.count(agentId, Narration.TurnEnded.class) == 1);
    assertThat(decisions.forCase(exceptionId)).isEmpty();
    assertThat(narration.count(agentId, Narration.CallApproved.class)).isZero();
    assertThat(model.outcomesSeen())
        .isNotEmpty()
        .noneMatch(ToolOutcome.Succeeded.class::isInstance);
  }
}

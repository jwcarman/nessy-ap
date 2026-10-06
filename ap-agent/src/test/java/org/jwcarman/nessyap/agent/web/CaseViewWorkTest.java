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
package org.jwcarman.nessyap.agent.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.inference.InferenceResult;
import org.jwcarman.nessyap.agent.ApAgentIntegrationTest;
import org.jwcarman.nessyap.agent.cases.CaseInput;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** The case view says what the case's agent is doing, from Nessy's account of its work. */
class CaseViewWorkTest extends ApAgentIntegrationTest {

  private static final TestingAuthenticationToken CONNIE =
      new TestingAuthenticationToken(
          "connie", "n/a", List.of(new SimpleGrantedAuthority("ROLE_controller")));

  @Autowired QueuedHarness<CaseInput> agent;
  @Autowired CaseController caseController;

  @Test
  void an_input_told_while_the_agent_works_is_counted_as_queued() throws InterruptedException {
    UUID exceptionId = openCase();
    AgentId agentId = caseIndex.agentFor(exceptionId);
    CountDownLatch inTurn = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    model.script(
        request -> {
          inTurn.countDown();
          try {
            release.await(20, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return new InferenceResult.Answer(List.of(new Block.Text("Noted.")));
        });
    try {
      agent.tell(agentId, new CaseInput.PersonNote("clara", "first"));
      assertThat(inTurn.await(20, TimeUnit.SECONDS)).isTrue();

      agent.tell(agentId, new CaseInput.PersonNote("clara", "second"));

      await()
          .atMost(Duration.ofSeconds(10))
          .until(() -> caseController.get(exceptionId, CONNIE).queued() == 1);
      assertThat(caseController.get(exceptionId, CONNIE).agentActive()).isTrue();
    } finally {
      release.countDown();
    }
  }
}

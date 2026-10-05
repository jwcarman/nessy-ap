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
package org.jwcarman.nessyap.agent.decisions;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessyap.agent.ApAgentIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;

/** A proposal and what produced it are recorded together, once for each call. */
class DecisionProvenanceTest extends ApAgentIntegrationTest {

  @Autowired Decisions decisions;

  private static PendingDecision proposal(UUID id, UUID exceptionId, UUID key) {
    return new PendingDecision(
        id,
        new AgentId(UUID.randomUUID()),
        key,
        "agent",
        exceptionId,
        UUID.randomUUID(),
        "hold",
        null,
        "r",
        List.of(),
        Instant.now().plusSeconds(60),
        DecisionStatus.PENDING,
        null,
        null,
        null,
        null,
        null,
        null,
        Instant.now(),
        "ap-clerk",
        null);
  }

  @Test
  void a_proposal_is_recorded_with_its_provenance() {
    UUID exceptionId = openCase();
    UUID id = UUID.randomUUID();

    decisions.insert(proposal(id, exceptionId, UUID.randomUUID()), "{\"proposer\":\"agent\"}");

    assertThat(decisions.provenance(id))
        .hasValueSatisfying(p -> assertThat(p.proposer()).isEqualTo("agent"));
  }

  @Test
  void a_call_asked_again_keeps_its_decision_and_its_first_provenance() {
    UUID exceptionId = openCase();
    UUID key = UUID.randomUUID();
    UUID first = UUID.randomUUID();
    decisions.insert(proposal(first, exceptionId, key), "{\"proposer\":\"agent\"}");

    // The engine asks again about the same call after a restart, under a new decision id.
    decisions.insert(proposal(UUID.randomUUID(), exceptionId, key), "{\"proposer\":\"rules\"}");

    assertThat(decisions.forCase(exceptionId))
        .singleElement()
        .extracting(PendingDecision::id)
        .isEqualTo(first);
    assertThat(decisions.provenance(first))
        .hasValueSatisfying(p -> assertThat(p.proposer()).isEqualTo("agent"));
  }
}

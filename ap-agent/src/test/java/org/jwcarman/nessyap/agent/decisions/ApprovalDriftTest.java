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
import static org.awaitility.Awaitility.await;
import static org.jwcarman.nessyap.agent.ScriptedProvider.call;
import static org.jwcarman.nessyap.agent.ScriptedProvider.steps;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
import org.jwcarman.nessyap.agent.ApAgentIntegrationTest;
import org.jwcarman.nessyap.agent.cases.CaseInput;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The desk's proposals and Nessy's parked approvals must agree. A proposal a person can decide that
 * no call waits on, or a call that waits with no proposal anyone can see, is drift.
 */
class ApprovalDriftTest extends ApAgentIntegrationTest {

  private static final Duration LATER = Duration.ofMinutes(2);

  @Autowired Decisions decisions;
  @Autowired ApprovalDrift drift;
  @Autowired QueuedHarness<CaseInput> agent;

  private static PendingDecision proposal(UUID exceptionId, Instant deadline) {
    return new PendingDecision(
        UUID.randomUUID(),
        new AgentId(UUID.randomUUID()),
        UUID.randomUUID(),
        "agent",
        exceptionId,
        UUID.randomUUID(),
        "hold",
        null,
        "r",
        List.of(),
        deadline,
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

  private PendingDecision proposedByTheAgent() {
    erp.onPrefix(
        "GET",
        "/api/invoices/",
        200,
        "{\"invoice\":{\"status\":\"EXCEPTION\",\"version\":1},\"exceptions\":[]}");
    model.script(
        steps(
            call(
                "c1",
                "propose_resolution",
                "{\"action\":\"hold\",\"rationale\":\"waiting on goods\",\"evidence\":[]}")));
    UUID exceptionId = openCase();
    agent.tell(caseIndex.agentFor(exceptionId), new CaseInput.PersonNote("clara", "look"));
    return await()
        .atMost(Duration.ofSeconds(20))
        .until(() -> decisions.forCase(exceptionId), list -> !list.isEmpty())
        .getFirst();
  }

  @Test
  void a_proposal_that_no_call_waits_on_is_drift() {
    PendingDecision orphan = proposal(openCase(), Instant.now().plus(Duration.ofHours(1)));
    decisions.insert(orphan, "{\"proposer\":\"agent\"}");

    assertThat(drift.check(Instant.now().plus(LATER)).unheld()).contains(orphan.id());
  }

  @Test
  void a_proposal_past_its_deadline_is_a_late_decision_not_drift() {
    PendingDecision late = proposal(openCase(), Instant.now().plusSeconds(30));
    decisions.insert(late, "{\"proposer\":\"agent\"}");

    assertThat(drift.check(Instant.now().plus(LATER)).unheld()).doesNotContain(late.id());
  }

  @Test
  void a_proposal_too_new_to_judge_is_not_drift() {
    PendingDecision fresh = proposal(openCase(), Instant.now().plus(Duration.ofHours(1)));
    decisions.insert(fresh, "{\"proposer\":\"agent\"}");

    assertThat(drift.check(Instant.now()).unheld()).doesNotContain(fresh.id());
  }

  @Test
  void a_proposal_its_call_waits_on_is_not_drift() {
    PendingDecision proposed = proposedByTheAgent();

    ApprovalDrift.Drift found = drift.check(Instant.now().plus(LATER));

    assertThat(found.unheld()).doesNotContain(proposed.id());
    assertThat(found.unseen()).doesNotContain(IdempotencyKey.of(proposed.idempotencyKey()));
  }

  @Test
  void a_call_that_waits_with_no_proposal_is_drift() {
    PendingDecision proposed = proposedByTheAgent();
    jdbc.sql("delete from decision_provenance where decision_id = :id")
        .param("id", proposed.id())
        .update();
    jdbc.sql("delete from pending_decision where id = :id").param("id", proposed.id()).update();

    assertThat(drift.check(Instant.now().plus(LATER)).unseen())
        .contains(IdempotencyKey.of(proposed.idempotencyKey()));
  }
}

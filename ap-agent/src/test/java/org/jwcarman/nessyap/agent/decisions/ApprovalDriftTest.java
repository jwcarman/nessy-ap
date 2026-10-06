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

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentStatus;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.AgentWork;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessyap.agent.AgentConfiguration;
import org.jwcarman.nessyap.agent.ApAgentIntegrationTest;
import org.jwcarman.nessyap.agent.cases.CaseInput;
import org.jwcarman.nessyap.agent.oversight.DeskMetrics;
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
  @Autowired AgentWork work;

  private static PendingDecision proposal(UUID exceptionId, Instant deadline) {
    return proposal(exceptionId, deadline, "agent");
  }

  private static PendingDecision proposal(UUID exceptionId, Instant deadline, String proposer) {
    return new PendingDecision(
        UUID.randomUUID(),
        new AgentId(UUID.randomUUID()),
        UUID.randomUUID(),
        proposer,
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

  /**
   * A proposal from the agent, once Nessy has parked its call. The desk records the proposal before
   * Nessy parks the call, so a check in between would see only half of it.
   */
  private PendingDecision proposedAndParked() {
    PendingDecision proposed = proposedByTheAgent();
    IdempotencyKey key = IdempotencyKey.of(proposed.idempotencyKey());
    await()
        .atMost(Duration.ofSeconds(20))
        .until(
            () ->
                work.waitingApprovals(AgentConfiguration.AGENT_TYPE).stream()
                    .anyMatch(request -> request.idempotencyKey().equals(key)));
    return proposed;
  }

  private static ApprovalRequest waitingOn(IdempotencyKey key) {
    return new ApprovalRequest(
        AgentConfiguration.AGENT_TYPE,
        new AgentId(UUID.randomUUID()),
        new TurnId(1),
        new CallId("c1"),
        key,
        new ToolName("propose_resolution"),
        "{}",
        "hold",
        Instant.now().minus(Duration.ofHours(1)),
        Instant.now().plus(Duration.ofHours(1)));
  }

  /** A check over a Nessy that lists what the supplier gives, each time it is asked. */
  private ApprovalDrift over(Supplier<List<ApprovalRequest>> waiting) {
    AgentWork listed =
        new AgentWork() {
          @Override
          public AgentStatus status(AgentType type, AgentId id) {
            throw new UnsupportedOperationException("not used");
          }

          @Override
          public List<ApprovalRequest> waitingApprovals() {
            return waiting.get();
          }

          @Override
          public List<ApprovalRequest> waitingApprovals(AgentType type) {
            return waiting.get();
          }
        };
    return new ApprovalDrift(
        decisions,
        listed,
        new DeskMetrics(new SimpleMeterRegistry()),
        Clock.systemUTC(),
        Duration.ofMinutes(1));
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
    PendingDecision proposed = proposedAndParked();

    ApprovalDrift.Drift found = drift.check(Instant.now().plus(LATER));

    assertThat(found.unheld()).doesNotContain(proposed.id());
    assertThat(found.unseen()).doesNotContain(IdempotencyKey.of(proposed.idempotencyKey()));
  }

  @Test
  void a_call_that_waits_with_no_proposal_is_drift() {
    PendingDecision proposed = proposedAndParked();
    jdbc.sql("delete from decision_provenance where decision_id = :id")
        .param("id", proposed.id())
        .update();
    jdbc.sql("delete from pending_decision where id = :id").param("id", proposed.id()).update();

    assertThat(drift.check(Instant.now().plus(LATER)).unseen())
        .contains(IdempotencyKey.of(proposed.idempotencyKey()));
  }

  @Test
  void a_waiting_call_whose_proposal_is_decided_but_not_carried_through_is_not_drift() {
    PendingDecision proposed = proposedAndParked();
    decisions.markDecided(proposed.id(), "clara", true, null, Instant.now());

    assertThat(drift.check(Instant.now().plus(LATER)).unseen())
        .doesNotContain(IdempotencyKey.of(proposed.idempotencyKey()));
  }

  @Test
  void a_proposal_the_rules_made_is_not_drift() {
    PendingDecision byRules =
        proposal(openCase(), Instant.now().plus(Duration.ofHours(1)), PendingDecision.BY_RULES);
    decisions.insert(byRules, "{\"proposer\":\"rules\"}");

    assertThat(drift.check(Instant.now().plus(LATER)).unheld()).doesNotContain(byRules.id());
  }

  @Test
  void a_proposal_beyond_the_most_waiting_approvals_nessy_lists_is_not_called_unheld() {
    PendingDecision beyond = proposal(openCase(), Instant.now().plus(Duration.ofHours(1)));
    decisions.insert(beyond, "{\"proposer\":\"agent\"}");
    List<ApprovalRequest> full =
        IntStream.range(0, ApprovalDrift.MOST_LISTED)
            .mapToObj(i -> waitingOn(IdempotencyKey.of(UUID.randomUUID())))
            .toList();

    assertThat(over(() -> full).check(Instant.now().plus(LATER)).unheld())
        .doesNotContain(beyond.id());
  }

  @Test
  void a_proposal_answered_while_the_check_reads_is_not_drift() {
    PendingDecision answered = proposal(openCase(), Instant.now().plus(Duration.ofHours(1)));
    decisions.insert(answered, "{\"proposer\":\"agent\"}");
    ApprovalDrift racing =
        over(
            () -> {
              decisions.markAnswered(answered.id(), "applied");
              return List.of();
            });

    assertThat(racing.check(Instant.now().plus(LATER)).unheld()).doesNotContain(answered.id());
  }
}

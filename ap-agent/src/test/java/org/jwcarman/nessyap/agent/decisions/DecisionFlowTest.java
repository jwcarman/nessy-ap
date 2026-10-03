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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.jwcarman.nessyap.agent.ScriptedProvider.call;
import static org.jwcarman.nessyap.agent.ScriptedProvider.steps;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessyap.agent.ApAgentIntegrationTest;
import org.jwcarman.nessyap.agent.cases.CaseInput;
import org.jwcarman.nessyap.agent.cases.CaseStatus;
import org.jwcarman.nessyap.agent.cases.CaseTimeline;
import org.jwcarman.nessyap.agent.cases.Cases;
import org.jwcarman.nessyap.agent.erp.ErpStub;
import org.jwcarman.nessyap.contracts.MatchExceptionRaised;
import org.jwcarman.nessyap.contracts.ReasonCode;
import org.springframework.beans.factory.annotation.Autowired;

class DecisionFlowTest extends ApAgentIntegrationTest {

  static final Duration PATIENCE = Duration.ofSeconds(20);
  static final UUID INVOICE = UUID.fromString("01a0ffe1-29f3-7457-88d0-bc59aa5c810b");
  static final String INVOICE_JSON =
      "{\"invoice\":{\"id\":\""
          + INVOICE
          + "\",\"status\":\"EXCEPTION\",\"version\":1,"
          + "\"total\":1040.00,\"approvedAmount\":null},\"exceptions\":[]}";
  static final String APPROVED_JSON =
      "{\"invoice\":{\"id\":\""
          + INVOICE
          + "\",\"status\":\"APPROVED\",\"version\":2,"
          + "\"total\":1040.00,\"approvedAmount\":1040.00},\"exceptions\":[]}";

  @Autowired QueuedHarness<CaseInput> agent;
  @Autowired Cases cases;
  @Autowired CaseTimeline timeline;
  @Autowired Decisions decisions;
  @Autowired DecisionExecutor executor;
  @Autowired DecisionSweeper sweeper;

  private UUID exceptionId;
  private AgentId agentId;

  @BeforeEach
  void anAgentThatInvestigatesThenProposes() {
    model.script(
        steps(
            call("c1", "get_invoice", "{\"invoiceId\":\"" + INVOICE + "\"}"),
            call(
                "c2",
                "propose_resolution",
                "{\"action\":\"approve-variance\",\"rationale\":\"buyer agreed the new price\","
                    + "\"evidence\":[\""
                    + INVOICE
                    + "\"]}")));
    erp.on("GET", "/api/invoices/" + INVOICE, 200, INVOICE_JSON);
    exceptionId = UUID.randomUUID();
    MatchExceptionRaised raised =
        new MatchExceptionRaised(
            UUID.randomUUID(),
            Instant.now(),
            exceptionId,
            INVOICE,
            "INV-1001",
            UUID.randomUUID(),
            "PO-1",
            ReasonCode.PRICE_VARIANCE,
            "Line 1 billed 10.40 against PO price 10.00",
            new BigDecimal("40.00"));
    cases.open(raised);
    agentId = cases.agentFor(exceptionId);
    agent.tell(agentId, new CaseInput.ExceptionRaised(raised));
  }

  private PendingDecision awaitProposal() {
    return await()
        .atMost(PATIENCE)
        .until(() -> decisions.forCase(exceptionId), list -> !list.isEmpty())
        .getFirst();
  }

  private void awaitTurnEnded(long turns) {
    await()
        .atMost(PATIENCE)
        .until(() -> narration.count(agentId, Narration.TurnEnded.class) == turns);
  }

  private List<ErpStub.Seen> posts() {
    return erp.seen().stream()
        .filter(seen -> seen.method().equals("POST") && seen.target().startsWith("/api/"))
        .toList();
  }

  @Test
  void a_proposal_waits_for_a_decision() {
    PendingDecision proposal = awaitProposal();

    assertThat(proposal.status()).isEqualTo(DecisionStatus.PENDING);
    assertThat(proposal.action()).isEqualTo("approve-variance");
    assertThat(proposal.rationale()).isEqualTo("buyer agreed the new price");
    assertThat(cases.find(exceptionId).orElseThrow().status())
        .isEqualTo(CaseStatus.AWAITING_DECISION);
    assertThat(posts()).isEmpty();
  }

  @Test
  void an_approval_is_carried_out_in_the_erp_under_the_decision_id() {
    PendingDecision proposal = awaitProposal();
    erp.on("POST", "/api/invoices/" + INVOICE + "/approve-variance", 200, APPROVED_JSON);

    executor.decide(proposal.id(), "connie", true, "fine");

    awaitTurnEnded(1);
    assertThat(posts())
        .singleElement()
        .satisfies(
            post -> {
              assertThat(post.header("Idempotency-Key")).isEqualTo(proposal.id().toString());
              assertThat(post.body()).contains("\"expectedVersion\":1");
            });
    assertThat(decisions.find(proposal.id()).orElseThrow().status())
        .isEqualTo(DecisionStatus.ANSWERED);
    assertThat(cases.find(exceptionId).orElseThrow().status()).isEqualTo(CaseStatus.RESOLVED);
    assertThat(timeline.of(exceptionId))
        .extracting(CaseTimeline.CaseEvent::kind)
        .containsSubsequence("tool", "proposal", "decision", "resolved");
    assertThat(model.outcomesSeen())
        .filteredOn(ToolOutcome.Succeeded.class::isInstance)
        .isNotEmpty();
  }

  @Test
  void a_person_saying_no_is_a_denial_the_model_reads() {
    PendingDecision proposal = awaitProposal();

    executor.decide(proposal.id(), "connie", false, "call the buyer first");

    awaitTurnEnded(1);
    assertThat(posts()).isEmpty();
    assertThat(model.outcomesSeen())
        .filteredOn(ToolOutcome.Denied.class::isInstance)
        .singleElement()
        .satisfies(
            o -> assertThat(((ToolOutcome.Denied) o).reason()).contains("call the buyer first"));
    assertThat(cases.find(exceptionId).orElseThrow().status()).isEqualTo(CaseStatus.INVESTIGATING);
  }

  @Nested
  class When_the_erp_refuses {

    @Test
    void a_stale_version_comes_back_as_a_denial_naming_the_code() {
      PendingDecision proposal = awaitProposal();
      erp.on(
          "POST",
          "/api/invoices/" + INVOICE + "/approve-variance",
          409,
          "{\"status\":409,\"code\":\"STALE_VERSION\",\"detail\":\"read it again\"}");

      executor.decide(proposal.id(), "connie", true, "fine");

      awaitTurnEnded(1);
      assertThat(model.outcomesSeen())
          .filteredOn(ToolOutcome.Denied.class::isInstance)
          .singleElement()
          .satisfies(o -> assertThat(((ToolOutcome.Denied) o).reason()).contains("STALE_VERSION"));
      assertThat(cases.find(exceptionId).orElseThrow().status()).isNotEqualTo(CaseStatus.RESOLVED);
    }

    @Test
    void an_unverified_bank_change_comes_back_as_a_denial_naming_the_code() {
      PendingDecision proposal = awaitProposal();
      erp.on(
          "POST",
          "/api/invoices/" + INVOICE + "/approve-variance",
          422,
          "{\"status\":422,\"code\":\"BANK_CHANGE_UNVERIFIED\",\"detail\":\"no payment\"}");

      executor.decide(proposal.id(), "connie", true, "fine");

      awaitTurnEnded(1);
      assertThat(model.outcomesSeen())
          .filteredOn(ToolOutcome.Denied.class::isInstance)
          .singleElement()
          .satisfies(
              o ->
                  assertThat(((ToolOutcome.Denied) o).reason()).contains("BANK_CHANGE_UNVERIFIED"));
    }
  }

  @Test
  void an_erp_outage_leaves_the_decision_for_the_sweeper_which_retries_it_safely() {
    PendingDecision proposal = awaitProposal();
    erp.on("POST", "/api/invoices/" + INVOICE + "/approve-variance", 503, "{\"code\":\"DOWN\"}");

    executor.decide(proposal.id(), "connie", true, "fine");

    assertThat(decisions.find(proposal.id()).orElseThrow().status())
        .isEqualTo(DecisionStatus.DECIDED);
    erp.on("POST", "/api/invoices/" + INVOICE + "/approve-variance", 200, APPROVED_JSON);
    erp.on("GET", "/api/invoices/" + INVOICE, 200, APPROVED_JSON);

    sweeper.sweep(Duration.ZERO);

    awaitTurnEnded(1);
    assertThat(decisions.find(proposal.id()).orElseThrow().status())
        .isEqualTo(DecisionStatus.ANSWERED);
    assertThat(posts())
        .hasSize(2)
        .allSatisfy(
            post -> {
              assertThat(post.header("Idempotency-Key")).isEqualTo(proposal.id().toString());
              assertThat(post.body()).contains("\"expectedVersion\":1");
            });
  }

  @Test
  void a_rollback_after_the_erp_said_yes_leaves_the_decision_for_the_sweeper_to_repeat_exactly() {
    PendingDecision proposal = awaitProposal();
    erp.on("POST", "/api/invoices/" + INVOICE + "/approve-variance", 200, APPROVED_JSON);
    // Fails the carry-through after the ERP command has succeeded: the decision's timeline row.
    jdbc.sql(
            """
            create or replace function fail_decision_events() returns trigger language plpgsql as
            $body$ begin raise exception 'simulated crash after the ERP write'; end $body$
            """)
        .update();
    jdbc.sql(
            """
            create trigger fail_decision_events before insert on case_event for each row
            when (new.kind = 'decision') execute function fail_decision_events()
            """)
        .update();
    try {
      assertThatThrownBy(() -> executor.decide(proposal.id(), "connie", true, "fine"))
          .isInstanceOf(RuntimeException.class);
    } finally {
      jdbc.sql("drop trigger fail_decision_events on case_event").update();
    }

    PendingDecision recorded = decisions.find(proposal.id()).orElseThrow();
    assertThat(recorded.status()).isEqualTo(DecisionStatus.DECIDED);
    assertThat(recorded.expectedVersion()).isEqualTo(1L);

    sweeper.sweep(Duration.ZERO);

    awaitTurnEnded(1);
    assertThat(posts())
        .hasSize(2)
        .allSatisfy(
            post -> {
              assertThat(post.header("Idempotency-Key")).isEqualTo(proposal.id().toString());
              assertThat(post.body()).contains("\"expectedVersion\":1");
            });
    assertThat(posts().get(0).body()).isEqualTo(posts().get(1).body());
    assertThat(decisions.find(proposal.id()).orElseThrow().status())
        .isEqualTo(DecisionStatus.ANSWERED);
    assertThat(model.outcomesSeen())
        .filteredOn(ToolOutcome.Succeeded.class::isInstance)
        .isNotEmpty();
  }

  @Test
  void a_second_deciders_token_never_carries_out_the_first_deciders_decision() {
    PendingDecision proposal = awaitProposal();
    erp.on("POST", "/api/invoices/" + INVOICE + "/approve-variance", 503, "{\"code\":\"DOWN\"}");
    executor.decide(proposal.id(), "connie", true, "fine", "token-of-connie");
    erp.on("POST", "/api/invoices/" + INVOICE + "/approve-variance", 200, APPROVED_JSON);

    executor.decide(proposal.id(), "mark", true, "me too", "token-of-mark");

    assertThat(posts())
        .extracting(post -> String.valueOf(post.header("Authorization")))
        .containsExactly("Bearer token-of-connie", "null");
  }

  @Test
  void an_erp_that_wants_a_person_keeps_the_decision_for_its_decider_to_carry_through() {
    PendingDecision proposal = awaitProposal();
    erp.on("POST", "/api/invoices/" + INVOICE + "/approve-variance", 503, "{\"code\":\"DOWN\"}");
    executor.decide(proposal.id(), "connie", true, "fine", "token-of-connie");
    PendingDecision stuck = decisions.find(proposal.id()).orElseThrow();
    assertThat(stuck.status()).isEqualTo(DecisionStatus.DECIDED);
    erp.on(
        "POST",
        "/api/invoices/" + INVOICE + "/approve-variance",
        401,
        "{\"status\":401,\"code\":\"HTTP_401\",\"detail\":\"no token\"}");

    sweeper.sweep(Duration.ZERO);

    PendingDecision waiting = decisions.find(proposal.id()).orElseThrow();
    assertThat(waiting.status()).isEqualTo(DecisionStatus.DECIDED);
    assertThat(waiting.erpResult()).isEqualTo(DecisionExecutor.NEEDS_THE_DECIDER);
    long postsBefore = posts().size();
    sweeper.sweep(Duration.ZERO);
    assertThat(posts()).as("the sweeper leaves it for the decider").hasSize((int) postsBefore);

    erp.on("POST", "/api/invoices/" + INVOICE + "/approve-variance", 200, APPROVED_JSON);
    assertThat(executor.retryAsDecider(proposal.id(), "connie", "token-of-connie")).isTrue();

    awaitTurnEnded(1);
    assertThat(decisions.find(proposal.id()).orElseThrow().status())
        .isEqualTo(DecisionStatus.ANSWERED);
    assertThat(posts().getLast().header("Authorization")).isEqualTo("Bearer token-of-connie");
  }

  @Test
  void only_the_decider_may_lend_their_authority_to_a_retry() {
    PendingDecision proposal = awaitProposal();
    erp.on("POST", "/api/invoices/" + INVOICE + "/approve-variance", 503, "{\"code\":\"DOWN\"}");
    executor.decide(proposal.id(), "connie", true, "fine", "token-of-connie");

    assertThat(executor.retryAsDecider(proposal.id(), "mark", "token-of-mark")).isFalse();
  }

  @Test
  void deciding_twice_changes_nothing_the_second_time() {
    PendingDecision proposal = awaitProposal();
    erp.on("POST", "/api/invoices/" + INVOICE + "/approve-variance", 200, APPROVED_JSON);

    executor.decide(proposal.id(), "connie", true, "fine");
    executor.decide(proposal.id(), "mark", false, "no");

    awaitTurnEnded(1);
    assertThat(posts()).hasSize(1);
    assertThat(decisions.find(proposal.id()).orElseThrow().decidedBy()).isEqualTo("connie");
  }
}

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

import java.time.Clock;
import java.util.UUID;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.Replies;
import org.jwcarman.nessy.api.tool.ReplyOutcome;
import org.jwcarman.nessyap.agent.cases.CaseInput;
import org.jwcarman.nessyap.agent.cases.CaseStatus;
import org.jwcarman.nessyap.agent.cases.CaseTimeline;
import org.jwcarman.nessyap.agent.cases.Cases;
import org.jwcarman.nessyap.agent.erp.ErpClient;
import org.jwcarman.nessyap.agent.erp.ErpOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Carries a decision through, as the workbench will (spec §3.4): the ERP command runs as the
 * decider, under the decision's id as its idempotency key, and only then is the waiting call
 * answered. All of it happens holding the decision row, so two deciders cannot both carry it out,
 * and Nessy's answer commits with the row that records it.
 */
@Component
public class DecisionExecutor {

  private static final Logger log = LoggerFactory.getLogger(DecisionExecutor.class);

  private final Decisions decisions;
  private final DecisionTargets targets;
  private final TransactionTemplate tx;
  private final Clock clock;

  public DecisionExecutor(
      Decisions decisions, DecisionTargets targets, TransactionTemplate tx, Clock clock) {
    this.decisions = decisions;
    this.targets = targets;
    this.tx = tx;
    this.clock = clock;
  }

  /**
   * Decides a proposal. A proposal already decided is carried through as first decided, whatever
   * this call says; one already answered is left alone.
   */
  public void decide(UUID decisionId, String decidedBy, boolean approve, String comment) {
    tx.executeWithoutResult(
        status -> {
          PendingDecision d =
              decisions
                  .lock(decisionId)
                  .orElseThrow(() -> new IllegalArgumentException("no decision " + decisionId));
          if (d.status() == DecisionStatus.ANSWERED) {
            return;
          }
          if (d.status() == DecisionStatus.PENDING) {
            decisions.markDecided(decisionId, decidedBy, approve, comment, clock.instant());
            d = decisions.find(decisionId).orElseThrow();
          }
          carryThrough(d);
        });
  }

  private void carryThrough(PendingDecision d) {
    if (!Boolean.TRUE.equals(d.approved())) {
      String reason =
          "Declined by "
              + d.decidedBy()
              + (d.decisionComment() == null ? "" : ": " + d.decisionComment());
      answer(d, ApprovalResult.deniedBy(reason, d.id().toString()), "declined", false);
      return;
    }
    Long version = d.expectedVersion();
    if (version == null) {
      ErpOutcome<JsonNode> invoice = targets.erp().invoice(d.invoiceId());
      switch (invoice) {
        case ErpOutcome.Ok<JsonNode>(JsonNode view) -> {
          version = view.path("invoice").path("version").asLong();
          decisions.rememberExpectedVersion(d.id(), version);
        }
        case ErpOutcome.Refused<JsonNode>(int s, String code, String detail) -> {
          refused(d, code, detail);
          return;
        }
        case ErpOutcome.Unavailable<JsonNode>(String reason) -> {
          log.info("ERP unavailable reading invoice for decision {}: {}", d.id(), reason);
          return;
        }
      }
    }
    ErpOutcome<JsonNode> outcome =
        targets
            .erp()
            .resolve(
                d.invoiceId(),
                d.action(),
                d.id().toString(),
                version,
                d.amount(),
                "Decided by " + d.decidedBy() + ": " + d.rationale());
    switch (outcome) {
      case ErpOutcome.Ok<JsonNode> ok ->
          answer(d, ApprovalResult.approvedBy(d.id().toString()), "applied", true);
      case ErpOutcome.Refused<JsonNode>(int s, String code, String detail) ->
          refused(d, code, detail);
      case ErpOutcome.Unavailable<JsonNode>(String reason) ->
          log.info(
              "ERP unavailable carrying out decision {}; the sweeper will retry: {}",
              d.id(),
              reason);
    }
  }

  private void refused(PendingDecision d, String code, String detail) {
    answer(
        d,
        ApprovalResult.deniedBy("ERP refused: " + code + ": " + detail, d.id().toString()),
        "ERP refused: " + code,
        false);
  }

  private void answer(PendingDecision d, ApprovalResult result, String erpResult, boolean applied) {
    ReplyOutcome reply = targets.replies().approve(d.replyToken(), result);
    decisions.markAnswered(d.id(), erpResult);
    targets
        .timeline()
        .record(
            d.exceptionId(),
            "decision",
            (Boolean.TRUE.equals(d.approved()) ? "approved by " : "declined by ")
                + d.decidedBy()
                + " -> "
                + erpResult);
    switch (reply) {
      case ReplyOutcome.Settled _ -> {
        if (!applied) {
          targets.cases().setStatus(d.exceptionId(), CaseStatus.INVESTIGATING);
        }
      }
      case ReplyOutcome.NotAwaiting _ -> {
        if (applied) {
          targets.cases().setStatus(d.exceptionId(), CaseStatus.RESOLVED);
          targets
              .agent()
              .tell(
                  d.agentId(),
                  new CaseInput.DecisionApplied(
                      d.id(), d.action(), "applied after the approval had expired"));
        }
      }
      case ReplyOutcome.Unreadable _ ->
          log.error("The reply token for decision {} could not be read", d.id());
    }
  }

  /** What a decision reaches once it is carried out. */
  @Component
  static final class DecisionTargets {

    private final ErpClient erp;
    private final Replies replies;
    private final QueuedHarness<CaseInput> agent;
    private final Cases cases;
    private final CaseTimeline timeline;

    DecisionTargets(
        ErpClient erp,
        Replies replies,
        QueuedHarness<CaseInput> agent,
        Cases cases,
        CaseTimeline timeline) {
      this.erp = erp;
      this.replies = replies;
      this.agent = agent;
      this.cases = cases;
      this.timeline = timeline;
    }

    ErpClient erp() {
      return erp;
    }

    Replies replies() {
      return replies;
    }

    QueuedHarness<CaseInput> agent() {
      return agent;
    }

    Cases cases() {
      return cases;
    }

    CaseTimeline timeline() {
      return timeline;
    }
  }
}

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
import org.jwcarman.nessyap.agent.resolver.ResolverDesk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
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

  /** What a decision is marked when the ERP wants its decider's own token to carry it through. */
  public static final String NEEDS_THE_DECIDER = "needs the decider";

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
   *
   * <p>Two transactions, on purpose. The first records the decision and the invoice version the ERP
   * command will name, and commits: from then on the row is the outbox, and a crash anywhere later
   * leaves it DECIDED for the sweeper, which repeats exactly the same command under the same key.
   * The second carries it through: the ERP command, the answer to the waiting call, and the row
   * marked answered, committing together.
   */
  public DecisionResult decide(UUID decisionId, String decidedBy, boolean approve, String comment) {
    return decide(decisionId, decidedBy, approve, comment, null);
  }

  /**
   * Decides as a person, whose access token (never stored) goes to the ERP with the command, so the
   * ERP sees who decided.
   */
  public DecisionResult decide(
      UUID decisionId, String decidedBy, boolean approve, String comment, String accessToken) {
    DecisionResult result =
        tx.execute(status -> recordDecision(decisionId, decidedBy, approve, comment));
    if (result instanceof DecisionResult.NoSuchDecision) {
      return result;
    }
    // Only the person who made the decision lends it their authority. Someone arriving after it
    // was decided finds it still owed and carries it through, but not as themselves.
    String authority = result instanceof DecisionResult.Decided ? accessToken : null;
    tx.executeWithoutResult(
        status ->
            decisions
                .lock(decisionId)
                .filter(d -> d.status() == DecisionStatus.DECIDED)
                .ifPresent(d -> carryThrough(d, authority)));
    return result;
  }

  /**
   * Carries a decided-but-unfinished decision through again with its decider's own token. Only the
   * person who decided may lend it their authority.
   *
   * @return false when this person is not the decider, or there is nothing left to carry through
   */
  public boolean retryAsDecider(UUID decisionId, String username, String accessToken) {
    Boolean done =
        tx.execute(
            status -> {
              var found =
                  decisions
                      .lock(decisionId)
                      .filter(d -> d.status() == DecisionStatus.DECIDED)
                      .filter(d -> username.equals(d.decidedBy()));
              found.ifPresent(d -> carryThrough(d, accessToken));
              return found.isPresent();
            });
    return Boolean.TRUE.equals(done);
  }

  private DecisionResult recordDecision(
      UUID decisionId, String decidedBy, boolean approve, String comment) {
    var found = decisions.lock(decisionId);
    if (found.isEmpty()) {
      return new DecisionResult.NoSuchDecision();
    }
    PendingDecision d = found.get();
    DecisionResult result = new DecisionResult.AlreadyDecided(d.decidedBy());
    if (d.status() == DecisionStatus.PENDING) {
      decisions.markDecided(decisionId, decidedBy, approve, comment, clock.instant());
      d = decisions.find(decisionId).orElseThrow();
      result = new DecisionResult.Decided();
    }
    if (d.status() != DecisionStatus.DECIDED
        || !Boolean.TRUE.equals(d.approved())
        || d.expectedVersion() != null
        || d.erpResult() != null) {
      return result;
    }
    switch (targets.erp().invoice(d.invoiceId())) {
      case ErpOutcome.Ok(JsonNode view) ->
          decisions.rememberExpectedVersion(d.id(), view.path("invoice").path("version").asLong());
      case ErpOutcome.Refused(int s, String code, String detail) ->
          decisions.rememberRefusal(d.id(), "ERP refused: " + code + ": " + detail);
      case ErpOutcome.Unavailable(String reason) ->
          log.info("ERP unavailable reading invoice for decision {}: {}", d.id(), reason);
    }
    return result;
  }

  private void carryThrough(PendingDecision d, String accessToken) {
    if (!Boolean.TRUE.equals(d.approved())) {
      // The decline arrives inside the turn that proposed. Without the second sentence a model
      // that obeys "propose once per turn" ends the turn here, and the case stalls.
      String reason =
          "Declined by "
              + d.decidedBy()
              + (d.decisionComment() == null ? "" : ": " + d.decisionComment())
              + ". This proposal is closed and the turn is still yours: check what the reason"
              + " points at, then propose again now.";
      answer(d, ApprovalResult.deniedBy(reason, d.id().toString()), "declined", false);
      return;
    }
    if (d.expectedVersion() == null) {
      if (d.erpResult() != null) {
        answer(d, ApprovalResult.deniedBy(d.erpResult(), d.id().toString()), d.erpResult(), false);
      }
      // Otherwise the ERP could not be read yet; the sweeper comes back for it.
      return;
    }
    ErpOutcome outcome =
        targets
            .erp()
            .resolve(
                d.invoiceId(),
                d.action(),
                d.id().toString(),
                d.expectedVersion(),
                d.amount(),
                "Decided by " + d.decidedBy() + ": " + d.rationale(),
                accessToken);
    switch (outcome) {
      case ErpOutcome.Ok ok ->
          answer(d, ApprovalResult.approvedBy(d.id().toString()), "applied", true);
      case ErpOutcome.Refused(int s, String code, String detail)
          when accessToken == null && (s == 401 || s == 403) -> {
        // The ERP wants a person and none is lending their authority (the sweeper, or someone
        // arriving after the decision). That is not the decider saying no: keep it for them.
        decisions.rememberRefusal(d.id(), NEEDS_THE_DECIDER);
        targets
            .timeline()
            .append(d.exceptionId(), "decision", "the ERP needs " + d.decidedBy() + " to retry");
      }
      case ErpOutcome.Refused(int s, String code, String detail) -> refused(d, code, detail);
      case ErpOutcome.Unavailable(String reason) ->
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
    if (ResolverDesk.TOKEN.equals(d.replyToken())) {
      // No agent waits on what the rules proposed: the rules hear of it once this commits.
      decisions.markAnswered(d.id(), erpResult);
      targets.timeline().append(d.exceptionId(), "decision", decided(d) + " -> " + erpResult);
      targets.events().publishEvent(new ResolverDesk.RulesDecided(d, applied));
      return;
    }
    ReplyOutcome reply = targets.replies().approve(d.replyToken(), result);
    decisions.markAnswered(d.id(), erpResult);
    targets.timeline().append(d.exceptionId(), "decision", decided(d) + " -> " + erpResult);
    switch (reply) {
      case ReplyOutcome.Settled _ -> {
        if (!applied) {
          targets.cases().setStatus(d.exceptionId(), CaseStatus.INVESTIGATING);
        }
      }
      case ReplyOutcome.NotAwaiting _ -> {
        if (applied) {
          targets.cases().setStatus(d.exceptionId(), CaseStatus.afterApplied(d.action()));
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

  private static String decided(PendingDecision d) {
    return (Boolean.TRUE.equals(d.approved()) ? "approved by " : "declined by ") + d.decidedBy();
  }

  /** What a decision reaches once it is carried out. */
  @Component
  static final class DecisionTargets {

    private final ErpClient erp;
    private final Replies replies;
    private final QueuedHarness<CaseInput> agent;
    private final Cases cases;
    private final CaseTimeline timeline;
    private final ApplicationEventPublisher events;

    DecisionTargets(
        ErpClient erp,
        Replies replies,
        QueuedHarness<CaseInput> agent,
        Cases cases,
        CaseTimeline timeline,
        ApplicationEventPublisher events) {
      this.events = events;
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

    ApplicationEventPublisher events() {
      return events;
    }
  }
}

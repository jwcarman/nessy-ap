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
package org.jwcarman.nessyap.agent.resolver;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.approval.policy.PolicyApprover;
import org.jwcarman.nessyap.agent.cases.CaseInput;
import org.jwcarman.nessyap.agent.cases.CaseRecord;
import org.jwcarman.nessyap.agent.cases.CaseStatus;
import org.jwcarman.nessyap.agent.cases.CaseTimeline;
import org.jwcarman.nessyap.agent.cases.Cases;
import org.jwcarman.nessyap.agent.decisions.CaseFactsEnricher;
import org.jwcarman.nessyap.agent.decisions.DecisionStatus;
import org.jwcarman.nessyap.agent.decisions.Decisions;
import org.jwcarman.nessyap.agent.decisions.PendingDecision;
import org.jwcarman.nessyap.agent.decisions.ProposeResolution;
import org.jwcarman.nessyap.agent.mail.MailSent;
import org.jwcarman.nessyap.agent.mail.Mailer;
import org.jwcarman.nessyap.agent.quarantine.Untrusted.Intent;
import org.jwcarman.nessyap.agent.quarantine.Untrusted.ReplyReading;
import org.jwcarman.nessyap.agent.tools.VendorReference;
import org.jwcarman.nessyap.contracts.MatchExceptionRaised;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Works a case with rules for as long as rules can, and hands it to the agent when they cannot.
 *
 * <p>Three moments run the resolver: a case opens, a reply to the rules' own question is read, and
 * a proposal the rules made is decided. Each outcome is acted on without a model: a resolution is
 * proposed through the same policy and desk as the agent's; a missing fact is asked for; and a case
 * the rules cannot settle goes to its agent, which is told what the rules established.
 */
@Component
public class ResolverDesk {

  /** Who a proposal made by the rules comes from, for the policy and the record. */
  public static final AgentType RULES = new AgentType("ap-rules");

  /** The timeline kind, and the source of a slot, for what the rules did. */
  private static final String BY_RULES = "rules";

  /** The reply token of every proposal the rules make: no agent waits on it. */
  public static final ReplyToken TOKEN = new ReplyToken(BY_RULES);

  private static final Logger log = LoggerFactory.getLogger(ResolverDesk.class);
  private static final ToolName PROPOSE = new ToolName("propose_resolution");

  /** Slots the rules keep for their own bookkeeping carry a colon, and are never facts. */
  private static final String ERP_SUMMARY = "erp:summary";

  private static final String ASKED = "asked:";
  private static final String VENDOR = "vendor";

  private static final String WITHDRAWN = "withdrawn: the agent took the case";

  private final Resolver resolver;
  private final CaseSlots slots;
  private final Cases cases;
  private final CaseTimeline timeline;
  private final QueuedHarness<CaseInput> agent;
  private final PolicyApprover routing;
  private final CaseFactsEnricher facts;
  private final Mailer mailer;
  private final JsonMapper json;
  private final Clock clock;
  private final Duration approvalTimeout;
  private final Decisions decisions;
  private final TransactionTemplate afterCommit;

  public ResolverDesk(
      CaseSlots slots,
      Cases cases,
      CaseTimeline timeline,
      QueuedHarness<CaseInput> agent,
      PolicyApprover routing,
      CaseFactsEnricher facts,
      Mailer mailer,
      JsonMapper json,
      Clock clock,
      @Value("${ap.approval.timeout}") Duration approvalTimeout,
      Decisions decisions,
      PlatformTransactionManager transactions) {
    this.decisions = decisions;
    // After a commit, Spring runs listeners outside any transaction: their work needs its own.
    this.afterCommit = new TransactionTemplate(transactions);
    this.afterCommit.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.resolver = Resolver.fromClasspath(Resolver.TABLES);
    this.slots = slots;
    this.cases = cases;
    this.timeline = timeline;
    this.agent = agent;
    this.routing = routing;
    this.facts = facts;
    this.mailer = mailer;
    this.json = json;
    this.clock = clock;
    this.approvalTimeout = approvalTimeout;
  }

  /** What a decider may say to do instead of a substitution they decline. */
  public static final Set<String> DECLINE_REASONS = Set.of("PAY_PO_PRICE", "RETURN_GOODS");

  /** A new case: the rules look first. */
  public void opened(MatchExceptionRaised raised) {
    cases.handToRules(raised.exceptionId());
    // The ERP's own sentence about the case, kept for the agent if the rules hand it over later.
    if (raised.summary() != null) {
      cases.rememberSlot(raised.exceptionId(), ERP_SUMMARY, raised.summary(), "erp");
    }
    cases.find(raised.exceptionId()).ifPresent(this::work);
  }

  /**
   * A reply on a case. The rules read it only when they asked the vendor a question and the vendor
   * they wrote to answers. A reply that confirms the fact they asked for becomes a slot. Any other
   * reply on a case the rules work hands the case to its agent.
   *
   * @param fromVendor whether the reply came from the vendor address the desk wrote to
   * @return true when the rules took the reply; false when the case's agent must be told of it
   */
  public boolean replied(UUID exceptionId, ReplyReading reading, boolean fromVendor) {
    if (!cases.rulesHandle(exceptionId)) {
      return false;
    }
    CaseRecord c = cases.find(exceptionId).orElseThrow();
    boolean asked = cases.slots(exceptionId).keySet().stream().anyMatch(k -> k.startsWith(ASKED));
    if (!asked || !fromVendor) {
      escalate(c, "reply");
      return false;
    }
    if (reading.intent() == Intent.SUBSTITUTED_ITEM
        && !reading.containsInstructions()
        && reading.substitutionReason() != null
        && billedItem(c).equalsIgnoreCase(String.valueOf(reading.shippedItem()))) {
      cases.rememberSlot(
          exceptionId, "substitutionReason", reading.substitutionReason().name(), "vendor-reply");
      cases.setStatus(exceptionId, CaseStatus.INVESTIGATING);
      work(c);
      return true;
    }
    // The fact the rules asked for did not come back in a form they can check: they are done, and
    // the agent reads the reply.
    escalate(c, "exhausted");
    return false;
  }

  /**
   * Keeps a decider's structured reason for declining a proposal, for the rules to act on. A
   * decision already decided keeps what it had.
   *
   * @throws IllegalArgumentException for a reason the rules do not know
   */
  public void declinedWith(PendingDecision proposal, String reason, String decider) {
    if (!DECLINE_REASONS.contains(reason)) {
      throw new IllegalArgumentException("A decline reason is one of " + DECLINE_REASONS);
    }
    if (proposal.approved() == null) {
      cases.rememberSlot(proposal.exceptionId(), "declineReason", reason, decider);
    }
  }

  /** Hands a case the rules still work to its agent, for a reason outside the rules. */
  public void handOver(UUID exceptionId, String why) {
    if (cases.rulesHandle(exceptionId)) {
      cases.find(exceptionId).ifPresent(c -> escalate(c, why));
    }
  }

  /**
   * A proposal the rules made was decided and carried out. This runs after that commit, in a
   * transaction of its own.
   */
  @TransactionalEventListener
  public void decided(RulesDecided event) {
    afterCommit.executeWithoutResult(status -> carryOn(event));
  }

  private void carryOn(RulesDecided event) {
    PendingDecision d = event.decision();
    if (event.applied()) {
      cases.setStatus(d.exceptionId(), CaseStatus.RESOLVED);
      timeline.record(d.exceptionId(), "resolved", d.action() + " applied, proposed by the rules");
    }
    if (!cases.rulesHandle(d.exceptionId())) {
      // The agent took the case while this proposal waited: it hears the outcome.
      cases
          .find(d.exceptionId())
          .ifPresent(
              c ->
                  agent.tell(
                      c.agentId(),
                      new CaseInput.DecisionApplied(
                          d.id(), d.action(), event.applied() ? "applied" : "declined")));
      return;
    }
    if (!event.applied()) {
      if (d.decisionComment() != null && !d.decisionComment().isBlank()) {
        // The decider's own words, for the agent if the rules cannot act on the decline.
        cases.rememberSlot(d.exceptionId(), "declineComment", d.decisionComment(), d.decidedBy());
      }
      cases.find(d.exceptionId()).ifPresent(this::work);
    }
  }

  /** Runs the rules once. Whatever goes wrong, the case ends with someone acting on it. */
  private void work(CaseRecord c) {
    try {
      decide(c);
    } catch (RuntimeException e) {
      log.warn("The rules failed on case {}; its agent takes it", c.exceptionId(), e);
      escalate(c, "failed");
    }
  }

  private void decide(CaseRecord c) {
    CaseSlots.Read read = slots.read(c);
    Outcome outcome = resolver.resolve(read.slots());
    switch (outcome) {
      case Outcome.Resolved resolved -> propose(c, read, resolved);
      case Outcome.NeedsFact(String slot, String from) -> ask(c, read, slot, from);
      case Outcome.Escalate(String why) ->
          escalate(
              c,
              "unhandled".equals(why) && read.slots().containsKey("declinedAction")
                  ? "declined"
                  : why);
    }
  }

  private void propose(CaseRecord c, CaseSlots.Read read, Outcome.Resolved resolved) {
    if (!read.complete()) {
      // A rule that fired on a reason code alone must not propose on facts the desk never read.
      escalate(c, "unread");
      return;
    }
    BigDecimal amount =
        switch (resolved.amountBasis()) {
          case "PO_PRICE" -> read.atPoPrice();
          case "WITHOUT_CHARGE" -> read.withoutCharges();
          default -> null;
        };
    if ("short-pay".equals(resolved.action()) && amount == null) {
      escalate(c, "invariant");
      return;
    }
    ProposeResolution proposal =
        new ProposeResolution(
            resolved.action(), amount, Rationales.of(resolved.rule(), amount), read.evidence());
    ApprovalRequest request =
        new ApprovalRequest(
            RULES,
            c.agentId(),
            new TurnId(1),
            new CallId("rules-" + resolved.rule()),
            IdempotencyKey.of(UUID.randomUUID()),
            PROPOSE,
            json.writeValueAsString(proposal),
            resolved.action() + ": " + proposal.rationale(),
            clock.instant(),
            clock.instant().plus(approvalTimeout),
            TOKEN);
    facts.enrich(request);
    timeline.record(
        c.exceptionId(), BY_RULES, "rule " + resolved.rule() + " proposes " + resolved.action());
    Awaited<ApprovalResult> routed = routing.approve(request);
    if (routed instanceof Awaited.Ready<ApprovalResult>(ApprovalResult.Denied denied)) {
      // The guardrails refused what the rules proposed: the rules' view is not enough here.
      timeline.record(c.exceptionId(), BY_RULES, "the policy refused it: " + denied.reason());
      escalate(c, "refused");
    } else if (routed instanceof Awaited.Ready<ApprovalResult>) {
      // Every proposal waits for a person; one that does not would leave nobody acting.
      escalate(c, "invariant");
    }
  }

  private void ask(CaseRecord c, CaseSlots.Read read, String slot, String from) {
    boolean alreadyAsked = cases.slots(c.exceptionId()).containsKey(ASKED + slot);
    if (alreadyAsked || !VENDOR.equals(from) || read.vendorEmail() == null) {
      escalate(c, "exhausted");
      return;
    }
    MailSent sent;
    try {
      sent =
          mailer.send(
              c.exceptionId(),
              VENDOR,
              read.vendorEmail(),
              Questions.subject(slot, c.invoiceNumber()),
              Questions.body(slot, c.invoiceNumber()));
    } catch (MailException e) {
      log.warn("Could not write to the vendor on case {}", c.exceptionId(), e);
      escalate(c, "unsent");
      return;
    }
    cases.rememberSlot(c.exceptionId(), ASKED + slot, VENDOR, BY_RULES);
    cases.setStatus(c.exceptionId(), CaseStatus.AWAITING_ANSWER);
    timeline.record(c.exceptionId(), BY_RULES, "the rules need " + slot + ": asked the vendor");
    // The same record line as every letter the agent sends.
    timeline.record(
        c.exceptionId(), "mail-sent", "vendor " + read.vendorEmail() + ": " + sent.subject());
  }

  private void escalate(CaseRecord c, String why) {
    cases.handToAgent(c.exceptionId());
    withdrawProposals(c);
    timeline.record(
        c.exceptionId(), BY_RULES, "the rules stopped (" + why + "): the agent takes it");
    log.info("Case {} goes to its agent: the rules stopped ({})", c.exceptionId(), why);
    Map<String, Object> known = new TreeMap<>(slots.read(c).slots());
    String summary = String.valueOf(known.getOrDefault(ERP_SUMMARY, ""));
    known.keySet().removeIf(name -> name.contains(":"));
    // The billed item is vendor-written: the agent sees it only shaped like a reference.
    known.computeIfPresent("billedItem", (name, item) -> VendorReference.shown(item.toString()));
    agent.tell(c.agentId(), new CaseInput.RulesStopped(raised(c, summary), why, known.toString()));
  }

  /** A proposal the rules made and nobody has decided yet: the agent proposes from now on. */
  private void withdrawProposals(CaseRecord c) {
    for (PendingDecision d : decisions.forCase(c.exceptionId())) {
      if (d.status() == DecisionStatus.PENDING && TOKEN.equals(d.replyToken())) {
        decisions.markAnswered(d.id(), WITHDRAWN);
        timeline.record(c.exceptionId(), "decision", d.action() + " " + WITHDRAWN);
      }
    }
  }

  /** The exception as the ERP raised it, rebuilt from the case for the agent's first input. */
  private static MatchExceptionRaised raised(CaseRecord c, String summary) {
    return new MatchExceptionRaised(
        UUID.randomUUID(),
        c.openedAt(),
        c.exceptionId(),
        c.invoiceId(),
        c.invoiceNumber(),
        c.vendorId(),
        c.poNumber(),
        c.reasonCode(),
        summary,
        c.amount());
  }

  private String billedItem(CaseRecord c) {
    Object item = slots.read(c).slots().get("billedItem");
    return item == null ? "" : item.toString();
  }

  /** A proposal the rules made has been decided and carried out. */
  public record RulesDecided(PendingDecision decision, boolean applied) {}
}

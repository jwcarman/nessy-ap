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
import java.util.Optional;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mail.MailException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
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
  private final ApplicationEventPublisher events;
  private final Duration stalledAfter;

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
      ApplicationEventPublisher events,
      @Value("${ap.rules.stalled-after:PT2M}") Duration stalledAfter) {
    this.decisions = decisions;
    this.events = events;
    this.stalledAfter = stalledAfter;
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

  // Every entry point below runs inside its caller's transaction, and only records what is due:
  // database writes, no HTTP. The rules read the ERP and call the policy after that commit, on
  // a thread of their own and with no transaction, so no connection is held across a remote call.
  // A case left with nothing in motion, by a crash between the two, is found by sweep().

  /** A new case: the rules look first, once it is committed. */
  public void opened(MatchExceptionRaised raised) {
    cases.handToRules(raised.exceptionId());
    // The ERP's own sentence about the case, kept for the agent if the rules hand it over later.
    if (raised.summary() != null) {
      cases.rememberSlot(raised.exceptionId(), ERP_SUMMARY, raised.summary(), "erp");
    }
    events.publishEvent(new RulesDue(raised.exceptionId()));
  }

  /**
   * A reply on a case. The rules use it only when they asked the vendor a question and the vendor
   * they wrote to answers with the fact they asked for. Any other reply on a case the rules work
   * hands the case to its agent, which is then told of the reply.
   *
   * @param fromVendor whether the reply came from the vendor address the desk wrote to
   * @param forAgent the reply as the agent would be told of it
   * @return true when the rules take the reply, and with it the duty to tell the agent; false when
   *     the case is its agent's, and the caller must tell it
   */
  public boolean replied(
      UUID exceptionId,
      ReplyReading reading,
      boolean fromVendor,
      CaseInput.CounterpartyReply forAgent) {
    if (!cases.rulesHandle(exceptionId)) {
      return false;
    }
    events.publishEvent(new RulesReply(exceptionId, reading, fromVendor, forAgent));
    return true;
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

  /**
   * Hands a case the rules still work to its agent, for a reason outside the rules.
   *
   * @param then what the agent is told right after the handover, or null
   * @return true when the rules had the case, and will tell the agent {@code then}; false when the
   *     case is already its agent's, and the caller must tell it
   */
  public boolean handOver(UUID exceptionId, String why, CaseInput then) {
    if (!cases.rulesHandle(exceptionId)) {
      return false;
    }
    events.publishEvent(new RulesHandOver(exceptionId, why, then));
    return true;
  }

  /** The rules run on a case that is due, after the commit that made it due. */
  @Async
  @TransactionalEventListener(fallbackExecution = true)
  public void onDue(RulesDue due) {
    ruled(due.exceptionId()).ifPresent(this::work);
  }

  /** The rules read a reply on their case, after the commit that received it. */
  @Async
  @TransactionalEventListener(fallbackExecution = true)
  public void onReply(RulesReply event) {
    ruled(event.exceptionId()).ifPresent(c -> read(c, event));
  }

  /** The rules give a case to its agent, after the commit that asked for it. */
  @Async
  @TransactionalEventListener(fallbackExecution = true)
  public void onHandOver(RulesHandOver event) {
    ruled(event.exceptionId()).ifPresent(c -> escalate(c, event.why(), event.then()));
  }

  /** A proposal the rules made was decided and carried out: the rules act after that commit. */
  @Async
  @TransactionalEventListener
  public void decided(RulesDecided event) {
    PendingDecision d = event.decision();
    if (event.applied()) {
      CaseStatus status = CaseStatus.afterApplied(d.action());
      cases.setStatus(d.exceptionId(), status);
      timeline.record(
          d.exceptionId(), status.timelineKind(), d.action() + " applied, proposed by the rules");
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

  /**
   * Runs the rules again on each case of theirs with nothing in motion: no proposal waiting, nobody
   * asked, no hold, and no change for a while. A process that stopped between a commit and the
   * rules' next step leaves such a case.
   */
  @Scheduled(fixedDelayString = "${ap.rules.sweep-interval-ms:30000}")
  public void sweep() {
    for (UUID exceptionId : cases.rulesStalled(clock.instant().minus(stalledAfter))) {
      log.info("Case {} had nothing in motion; the rules look again", exceptionId);
      ruled(exceptionId).ifPresent(this::work);
    }
  }

  private Optional<CaseRecord> ruled(UUID exceptionId) {
    return cases.rulesHandle(exceptionId) ? cases.find(exceptionId) : Optional.empty();
  }

  private void read(CaseRecord c, RulesReply event) {
    boolean asked =
        cases.slots(c.exceptionId()).keySet().stream().anyMatch(k -> k.startsWith(ASKED));
    if (!asked || !event.fromVendor()) {
      escalate(c, "reply", event.forAgent());
      return;
    }
    ReplyReading reading = event.reading();
    if (reading.intent() == Intent.SUBSTITUTED_ITEM
        && !reading.containsInstructions()
        && reading.substitutionReason() != null
        && billedItem(c).equalsIgnoreCase(String.valueOf(reading.shippedItem()))) {
      cases.rememberSlot(
          c.exceptionId(),
          "substitutionReason",
          reading.substitutionReason().name(),
          "vendor-reply");
      cases.setStatus(c.exceptionId(), CaseStatus.INVESTIGATING);
      work(c);
      return;
    }
    // The fact the rules asked for did not come back in a form they can check: they are done, and
    // the agent reads the reply.
    escalate(c, "exhausted", event.forAgent());
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
    escalate(c, why, null);
  }

  private void escalate(CaseRecord c, String why, CaseInput then) {
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
    if (then != null) {
      agent.tell(c.agentId(), then);
    }
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

  /** A case the rules must work, once the transaction that made it due commits. */
  public record RulesDue(UUID exceptionId) {}

  /** A reply on a case the rules work, with what the agent is told if they hand it over. */
  public record RulesReply(
      UUID exceptionId,
      ReplyReading reading,
      boolean fromVendor,
      CaseInput.CounterpartyReply forAgent) {}

  /** A case the rules must give to its agent, and what the agent is told next, or null. */
  public record RulesHandOver(UUID exceptionId, String why, CaseInput then) {}
}

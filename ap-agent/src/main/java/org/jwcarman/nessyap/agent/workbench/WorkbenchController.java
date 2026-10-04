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
package org.jwcarman.nessyap.agent.workbench;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessyap.agent.cases.CaseInput;
import org.jwcarman.nessyap.agent.cases.CaseRecord;
import org.jwcarman.nessyap.agent.cases.CaseTimeline;
import org.jwcarman.nessyap.agent.cases.Cases;
import org.jwcarman.nessyap.agent.decisions.Deciders;
import org.jwcarman.nessyap.agent.decisions.DecisionExecutor;
import org.jwcarman.nessyap.agent.decisions.DecisionResult;
import org.jwcarman.nessyap.agent.decisions.DecisionStatus;
import org.jwcarman.nessyap.agent.decisions.Decisions;
import org.jwcarman.nessyap.agent.decisions.Grounding;
import org.jwcarman.nessyap.agent.decisions.PendingDecision;
import org.jwcarman.nessyap.agent.decisions.PolicyConfig;
import org.jwcarman.nessyap.agent.decisions.Provenance;
import org.jwcarman.nessyap.agent.erp.ErpClient;
import org.jwcarman.nessyap.agent.erp.ErpOutcome;
import org.jwcarman.nessyap.agent.mail.UnmatchedMail;
import org.jwcarman.nessyap.agent.quarantine.Quarantine;
import org.jwcarman.nessyap.agent.quarantine.Untrusted.Reply;
import org.jwcarman.nessyap.agent.questions.Answers;
import org.jwcarman.nessyap.agent.questions.Question;
import org.jwcarman.nessyap.agent.questions.Questions;
import org.jwcarman.nessyap.agent.resolver.ResolverDesk;
import org.jwcarman.nessyap.agent.security.RealmRoles;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tools.jackson.databind.JsonNode;

/**
 * The AP workbench: the worklist, a case with its evidence and timeline, and the decisions the
 * signed-in person may make. Deciding here is the same decision the executor carries through for
 * anyone; the person's own access token goes to the ERP with the command.
 */
@Controller
@RequestMapping("/workbench")
public class WorkbenchController {

  private final Cases cases;
  private final CaseTimeline timeline;
  private final Decisions decisions;
  private final DecisionExecutor executor;
  private final ErpClient erp;
  private final QueuedHarness<CaseInput> agent;
  private final UnmatchedMail unmatched;
  private final Quarantine quarantine;
  private final Questions questions;
  private final Answers answers;
  private final Grounding grounding;
  private final ResolverDesk resolverDesk;

  public WorkbenchController(
      Cases cases,
      CaseTimeline timeline,
      Decisions decisions,
      DecisionExecutor executor,
      ErpClient erp,
      QueuedHarness<CaseInput> agent,
      UnmatchedMail unmatched,
      Quarantine quarantine,
      Questions questions,
      Answers answers,
      Grounding grounding,
      ResolverDesk resolverDesk) {
    this.resolverDesk = resolverDesk;
    this.cases = cases;
    this.timeline = timeline;
    this.decisions = decisions;
    this.executor = executor;
    this.erp = erp;
    this.agent = agent;
    this.unmatched = unmatched;
    this.quarantine = quarantine;
    this.questions = questions;
    this.answers = answers;
    this.grounding = grounding;
  }

  /** A pending decision as the worklist shows it: the decision and the case it belongs to. */
  public record Waiting(PendingDecision decision, CaseRecord kase) {}

  /** A question that waits for the signed-in person, with its case. */
  public record Asked(Question question, CaseRecord kase) {}

  @GetMapping
  public String worklist(Authentication me, Model model) {
    Set<String> roles = RealmRoles.of(me);
    List<PendingDecision> mine =
        roles.contains(Deciders.CONTROLLER)
            ? decisions.allPending()
            : decisions.pendingFor(roles, me.getName());
    model.addAttribute("me", me.getName());
    model.addAttribute("roles", roles);
    model.addAttribute(
        "waiting",
        mine.stream()
            .map(d -> cases.find(d.exceptionId()).map(c -> new Waiting(d, c)))
            .flatMap(Optional::stream)
            .toList());
    model.addAttribute(
        "asked",
        questions.waitingFor(me.getName()).stream()
            .map(q -> cases.find(q.exceptionId()).map(c -> new Asked(q, c)))
            .flatMap(Optional::stream)
            .toList());
    model.addAttribute("cases", cases.recent(50));
    // Sorting out mail no case claimed is a manager's job: it may be a new dispute or a fraud.
    model.addAttribute(
        "unmatched",
        roles.contains(Deciders.AP_MANAGER) || roles.contains(Deciders.CONTROLLER)
            ? unmatched.recent(20)
            : List.of());
    return "workbench/worklist";
  }

  @GetMapping("/cases/{exceptionId}")
  public String kase(@PathVariable UUID exceptionId, Authentication me, Model model) {
    CaseRecord c =
        cases
            .find(exceptionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such case"));
    Set<String> roles = RealmRoles.of(me);
    List<PendingDecision> all = decisions.forCase(exceptionId);
    model.addAttribute("me", me.getName());
    model.addAttribute("roles", roles);
    model.addAttribute("kase", c);
    model.addAttribute("integrity", cases.integrity(exceptionId));
    model.addAttribute("exceptionId", exceptionId);
    model.addAttribute("invoice", read(erp.invoice(c.invoiceId())));
    model.addAttribute(
        "purchaseOrder", c.poNumber() == null ? null : read(erp.purchaseOrder(c.poNumber())));
    model.addAttribute("receipts", c.poNumber() == null ? null : read(erp.receipts(c.poNumber())));
    model.addAttribute("timeline", timeline.of(exceptionId));
    model.addAttribute("decisions", all);
    model.addAttribute("questions", questions.forCase(exceptionId));
    // What the approver should know: citations the agent never actually read.
    model.addAttribute(
        "ungrounded",
        all.stream().collect(Collectors.toMap(PendingDecision::id, grounding::ungrounded)));
    // What produced each proposal, for the person who decides it and for the record.
    Map<UUID, Provenance.Stamp> made = new HashMap<>();
    all.forEach(d -> decisions.provenance(d.id()).ifPresent(p -> made.put(d.id(), p)));
    model.addAttribute("provenance", made);
    model.addAttribute(
        "decidable",
        all.stream()
            .filter(d -> d.status().name().equals("PENDING"))
            .filter(d -> Deciders.mayDecide(d, me.getName(), roles))
            .toList());
    return "workbench/case";
  }

  /**
   * Quarantined mail, for a person who works cases. The quarantine decides who may read it and
   * records each read; anybody else gets a 404, which says nothing about whether it exists.
   */
  @GetMapping("/mail/{handle}")
  public String mail(@PathVariable String handle, Model model) {
    Reply reply =
        quarantine
            .forPerson(handle)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such mail"));
    model.addAttribute("reply", reply);
    return "workbench/mail";
  }

  /** The timeline alone, for the case page to refresh in place. */
  @GetMapping("/cases/{exceptionId}/timeline")
  public String timeline(@PathVariable UUID exceptionId, Model model) {
    model.addAttribute("timeline", timeline.of(exceptionId));
    model.addAttribute("exceptionId", exceptionId);
    return "workbench/case :: timeline";
  }

  @PostMapping("/decisions/{decisionId}")
  public String decide(
      @PathVariable UUID decisionId,
      @RequestParam String verdict,
      @RequestParam(required = false) String comment,
      @RequestParam(required = false) String declineReason,
      Authentication me,
      @RegisteredOAuth2AuthorizedClient OAuth2AuthorizedClient client,
      RedirectAttributes redirect) {
    PendingDecision d =
        decisions
            .find(decisionId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such decision"));
    if (!Deciders.mayDecide(d, me.getName(), RealmRoles.of(me))) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "This decision belongs to " + d.requiredRole());
    }
    boolean approve = "approve".equals(verdict);
    if (!approve && (comment == null || comment.isBlank())) {
      redirect.addFlashAttribute(
          "message", "Say why: a denial needs a reason the agent can act on.");
      return "redirect:/workbench/cases/" + d.exceptionId();
    }
    if (!approve && declineReason != null && !declineReason.isBlank()) {
      resolverDesk.declinedWith(d, declineReason, me.getName());
    }
    DecisionResult result =
        executor.decide(
            decisionId,
            me.getName(),
            approve,
            comment,
            client == null ? null : client.getAccessToken().getTokenValue());
    redirect.addFlashAttribute(
        "message",
        switch (result) {
          case DecisionResult.Decided _ -> outcomeOf(decisionId, approve);
          case DecisionResult.AlreadyDecided(String by) ->
              "That was already decided by " + by + ".";
          case DecisionResult.NoSuchDecision _ -> "That decision no longer exists.";
        });
    return "redirect:/workbench/cases/" + d.exceptionId();
  }

  /**
   * Carries a decision through again as the person who made it, when the ERP wanted their own
   * authority and nobody was there to lend it (the sweeper, or the ERP was briefly down).
   */
  @PostMapping("/decisions/{decisionId}/retry")
  public String retry(
      @PathVariable UUID decisionId,
      Authentication me,
      @RegisteredOAuth2AuthorizedClient OAuth2AuthorizedClient client,
      RedirectAttributes redirect) {
    PendingDecision d =
        decisions
            .find(decisionId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such decision"));
    boolean carried =
        executor.retryAsDecider(
            decisionId,
            me.getName(),
            client == null ? null : client.getAccessToken().getTokenValue());
    redirect.addFlashAttribute(
        "message",
        carried
            ? "Carried out again: " + outcomeOf(decisionId, true)
            : "Only " + d.decidedBy() + ", who decided this, can carry it through.");
    return "redirect:/workbench/cases/" + d.exceptionId();
  }

  /** The person asked answers, signed in. Nobody else may; the answer goes to the agent. */
  @PostMapping("/questions/{questionId}/answer")
  public String answer(
      @PathVariable UUID questionId,
      @RequestParam(required = false) String choice,
      @RequestParam(required = false) String comment,
      Authentication me,
      RedirectAttributes redirect) {
    Answers.Answered result = answers.answer(questionId, me.getName(), choice, comment);
    String message =
        switch (result) {
          case Answers.Answered.Told _ -> "Sent to the agent.";
          case Answers.Answered.NotYours _ ->
              throw new ResponseStatusException(
                  HttpStatus.FORBIDDEN, "This question waits for someone else");
          case Answers.Answered.AlreadyAnswered _ -> "This question already has its answer.";
          case Answers.Answered.NotAChoice _ -> "Pick one of the choices.";
          case Answers.Answered.NeedsAnAnswer _ -> "Pick a choice or write an answer.";
          case Answers.Answered.NoSuchQuestion _ ->
              throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such question");
        };
    redirect.addFlashAttribute("message", message);
    return result instanceof Answers.Answered.Told(Question q)
        ? "redirect:/workbench/cases/" + q.exceptionId()
        : "redirect:/workbench";
  }

  @PostMapping("/cases/{exceptionId}/notes")
  public String note(
      @PathVariable UUID exceptionId,
      @RequestParam String text,
      Authentication me,
      RedirectAttributes redirect) {
    if (RealmRoles.of(me).stream().noneMatch(PolicyConfig.DECIDING_ROLES::contains)) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Only the people who decide cases may steer the agent");
    }
    CaseRecord c =
        cases
            .find(exceptionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such case"));
    if (!text.isBlank()) {
      timeline.record(exceptionId, "note", me.getName() + ": " + text);
      // A note is for the agent: a case the rules work becomes the agent's first.
      CaseInput.PersonNote note = new CaseInput.PersonNote(me.getName(), text);
      if (!resolverDesk.handOver(exceptionId, "person", note)) {
        agent.tell(c.agentId(), note);
      }
      redirect.addFlashAttribute("message", "Sent to the agent.");
    }
    return "redirect:/workbench/cases/" + exceptionId;
  }

  /** What the person is told: what the ERP actually did, not what they clicked. */
  private String outcomeOf(UUID decisionId, boolean approve) {
    PendingDecision after = decisions.find(decisionId).orElseThrow();
    if (!approve) {
      return "Denied. The agent has been told why.";
    }
    if (after.status() == DecisionStatus.DECIDED) {
      return "Approved, but the ERP could not be reached yet; it will be retried.";
    }
    String erpResult = after.erpResult() == null ? "" : after.erpResult();
    return erpResult.startsWith("ERP refused")
        ? "Approved, but the " + erpResult + ". The agent has been told."
        : "Approved and carried out in the ERP.";
  }

  private static JsonNode read(ErpOutcome<JsonNode> outcome) {
    return outcome instanceof ErpOutcome.Ok<JsonNode>(JsonNode value) ? value : null;
  }
}

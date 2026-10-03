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
package org.jwcarman.nessyap.agent.workbench;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessyap.agent.cases.CaseInput;
import org.jwcarman.nessyap.agent.cases.CaseRecord;
import org.jwcarman.nessyap.agent.cases.CaseTimeline;
import org.jwcarman.nessyap.agent.cases.Cases;
import org.jwcarman.nessyap.agent.decisions.Deciders;
import org.jwcarman.nessyap.agent.decisions.DecisionExecutor;
import org.jwcarman.nessyap.agent.decisions.DecisionResult;
import org.jwcarman.nessyap.agent.decisions.Decisions;
import org.jwcarman.nessyap.agent.decisions.PendingDecision;
import org.jwcarman.nessyap.agent.erp.ErpClient;
import org.jwcarman.nessyap.agent.erp.ErpOutcome;
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

  public WorkbenchController(
      Cases cases,
      CaseTimeline timeline,
      Decisions decisions,
      DecisionExecutor executor,
      ErpClient erp,
      QueuedHarness<CaseInput> agent) {
    this.cases = cases;
    this.timeline = timeline;
    this.decisions = decisions;
    this.executor = executor;
    this.erp = erp;
    this.agent = agent;
  }

  /** A pending decision as the worklist shows it: the decision and the case it belongs to. */
  public record Waiting(PendingDecision decision, CaseRecord kase) {}

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
    model.addAttribute("cases", cases.recent(50));
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
    model.addAttribute("exceptionId", exceptionId);
    model.addAttribute("invoice", read(erp.invoice(c.invoiceId())));
    model.addAttribute(
        "purchaseOrder", c.poNumber() == null ? null : read(erp.purchaseOrder(c.poNumber())));
    model.addAttribute("receipts", c.poNumber() == null ? null : read(erp.receipts(c.poNumber())));
    model.addAttribute("timeline", timeline.of(exceptionId));
    model.addAttribute("decisions", all);
    model.addAttribute(
        "decidable",
        all.stream()
            .filter(d -> d.status().name().equals("PENDING"))
            .filter(d -> Deciders.mayDecide(d, me.getName(), roles))
            .toList());
    return "workbench/case";
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
          case DecisionResult.Decided _ -> approve ? "Approved." : "Denied.";
          case DecisionResult.AlreadyDecided(String by) ->
              "That was already decided by " + by + ".";
          case DecisionResult.NoSuchDecision _ -> "That decision no longer exists.";
        });
    return "redirect:/workbench/cases/" + d.exceptionId();
  }

  @PostMapping("/cases/{exceptionId}/notes")
  public String note(
      @PathVariable UUID exceptionId,
      @RequestParam String text,
      Authentication me,
      RedirectAttributes redirect) {
    CaseRecord c =
        cases
            .find(exceptionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such case"));
    if (!text.isBlank()) {
      timeline.record(exceptionId, "note", me.getName() + ": " + text);
      agent.tell(c.agentId(), new CaseInput.PersonNote(me.getName(), text));
      redirect.addFlashAttribute("message", "Sent to the agent.");
    }
    return "redirect:/workbench/cases/" + exceptionId;
  }

  private static JsonNode read(ErpOutcome<JsonNode> outcome) {
    return outcome instanceof ErpOutcome.Ok<JsonNode>(JsonNode value) ? value : null;
  }
}

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

import static org.jwcarman.nessyap.agent.workbench.WorkbenchRedirects.MESSAGE;
import static org.jwcarman.nessyap.agent.workbench.WorkbenchRedirects.toCase;

import java.util.UUID;
import org.jwcarman.nessyap.agent.decisions.Deciders;
import org.jwcarman.nessyap.agent.decisions.DecisionExecutor;
import org.jwcarman.nessyap.agent.decisions.DecisionResult;
import org.jwcarman.nessyap.agent.decisions.DecisionStatus;
import org.jwcarman.nessyap.agent.decisions.Decisions;
import org.jwcarman.nessyap.agent.decisions.PendingDecision;
import org.jwcarman.nessyap.agent.resolver.ResolverDesk;
import org.jwcarman.nessyap.agent.security.RealmRoles;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.annotation.RegisteredOAuth2AuthorizedClient;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The decisions the signed-in person may make. Deciding here is the same decision the executor
 * carries through for anyone; the person's own access token goes to the ERP with the command.
 */
@Controller
@RequestMapping("/workbench")
class WorkbenchDecisionsController {

  private final Decisions decisions;
  private final DecisionExecutor executor;
  private final ResolverDesk resolverDesk;

  WorkbenchDecisionsController(
      Decisions decisions, DecisionExecutor executor, ResolverDesk resolverDesk) {
    this.decisions = decisions;
    this.executor = executor;
    this.resolverDesk = resolverDesk;
  }

  @PostMapping("/decisions/{decisionId}")
  String decide(
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
      redirect.addFlashAttribute(MESSAGE, "Say why: a denial needs a reason the agent can act on.");
      return toCase(d.exceptionId());
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
        MESSAGE,
        switch (result) {
          case DecisionResult.Decided _ -> outcomeOf(decisionId, approve);
          case DecisionResult.AlreadyDecided(String by) ->
              "That was already decided by " + by + ".";
          case DecisionResult.NoSuchDecision _ -> "That decision no longer exists.";
        });
    return toCase(d.exceptionId());
  }

  /**
   * Carries a decision through again as the person who made it, when the ERP wanted their own
   * authority and nobody was there to lend it (the sweeper, or the ERP was briefly down).
   */
  @PostMapping("/decisions/{decisionId}/retry")
  String retry(
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
        MESSAGE,
        carried
            ? "Carried out again: " + outcomeOf(decisionId, true)
            : "Only " + d.decidedBy() + ", who decided this, can carry it through.");
    return toCase(d.exceptionId());
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
}

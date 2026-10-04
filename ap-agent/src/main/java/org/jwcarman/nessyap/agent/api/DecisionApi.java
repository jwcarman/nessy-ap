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
package org.jwcarman.nessyap.agent.api;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jwcarman.nessyap.agent.cases.Cases;
import org.jwcarman.nessyap.agent.decisions.Deciders;
import org.jwcarman.nessyap.agent.decisions.DecisionExecutor;
import org.jwcarman.nessyap.agent.decisions.DecisionResult;
import org.jwcarman.nessyap.agent.decisions.Decisions;
import org.jwcarman.nessyap.agent.decisions.PendingDecision;
import org.jwcarman.nessyap.agent.security.RealmRoles;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The workbench's decisions for programs: the same rules, a bearer token instead of a session. */
@RestController
@RequestMapping("/api/decisions")
public class DecisionApi {

  /**
   * A decision on a proposal.
   *
   * @param declineReason for a declined substitution, what to do instead: {@code PAY_PO_PRICE} or
   *     {@code RETURN_GOODS}; the desk's rules act on it
   */
  public record DecideRequest(Boolean approve, String comment, String declineReason) {

    public DecideRequest(Boolean approve, String comment) {
      this(approve, comment, null);
    }
  }

  /** What a decider may say instead of a substitution they decline. */
  private static final Set<String> DECLINE_REASONS = Set.of("PAY_PO_PRICE", "RETURN_GOODS");

  public record DecideResponse(String result, String decidedBy) {}

  private final Decisions decisions;
  private final DecisionExecutor executor;
  private final Cases cases;

  public DecisionApi(Decisions decisions, DecisionExecutor executor, Cases cases) {
    this.cases = cases;
    this.decisions = decisions;
    this.executor = executor;
  }

  @GetMapping
  public List<DecisionView> mine(JwtAuthenticationToken me) {
    Set<String> roles = RealmRoles.of(me);
    List<PendingDecision> pending =
        roles.contains(Deciders.CONTROLLER)
            ? decisions.allPending()
            : decisions.pendingFor(roles, me.getName());
    return pending.stream().map(DecisionView::of).toList();
  }

  @PostMapping("/{decisionId}")
  public DecideResponse decide(
      @PathVariable UUID decisionId,
      @RequestBody DecideRequest request,
      JwtAuthenticationToken me) {
    PendingDecision d =
        decisions
            .find(decisionId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such decision"));
    if (!Deciders.mayDecide(d, me.getName(), RealmRoles.of(me))) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "This decision belongs to " + d.requiredRole());
    }
    boolean approve = Boolean.TRUE.equals(request.approve());
    if (!approve && (request.comment() == null || request.comment().isBlank())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A denial needs a reason");
    }
    if (request.declineReason() != null) {
      if (approve || !DECLINE_REASONS.contains(request.declineReason())) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "A decline reason is one of " + DECLINE_REASONS);
      }
      if (d.approved() == null) {
        cases.rememberSlot(d.exceptionId(), "declineReason", request.declineReason(), me.getName());
      }
    }
    return switch (executor.decide(
        decisionId, me.getName(), approve, request.comment(), me.getToken().getTokenValue())) {
      case DecisionResult.Decided _ -> new DecideResponse("DECIDED", me.getName());
      case DecisionResult.AlreadyDecided(String by) -> new DecideResponse("ALREADY_DECIDED", by);
      case DecisionResult.NoSuchDecision _ ->
          throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such decision");
    };
  }
}

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
package org.jwcarman.nessyap.agent.web;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jwcarman.nessyap.agent.cases.CaseRecord;
import org.jwcarman.nessyap.agent.cases.CaseTimeline;
import org.jwcarman.nessyap.agent.cases.Cases;
import org.jwcarman.nessyap.agent.decisions.Decisions;
import org.jwcarman.nessyap.agent.decisions.PendingDecision;
import org.jwcarman.nessyap.agent.security.RealmRoles;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * A case as the AP team reads it: where it stands, what happened, what was proposed. What the
 * evaluation polls, with a bearer token like any other program.
 */
@RestController
public class CaseController {

  /** A proposal and what became of it. */
  public record CaseDecision(
      UUID id,
      String action,
      String rationale,
      String requiredRole,
      String requiredUser,
      String status,
      String decidedBy,
      String erpResult) {

    static CaseDecision of(PendingDecision d) {
      return new CaseDecision(
          d.id(),
          d.action(),
          d.rationale(),
          d.requiredRole(),
          d.requiredUser(),
          d.status().name(),
          d.decidedBy(),
          d.erpResult());
    }
  }

  public record CaseView(
      UUID exceptionId,
      UUID agentId,
      UUID invoiceId,
      String reasonCode,
      String status,
      List<CaseTimeline.CaseEvent> timeline,
      List<CaseDecision> decisions) {}

  private final Cases cases;
  private final CaseTimeline timeline;
  private final Decisions decisions;

  public CaseController(Cases cases, CaseTimeline timeline, Decisions decisions) {
    this.cases = cases;
    this.timeline = timeline;
    this.decisions = decisions;
  }

  /** Who may read a case: anyone who works cases, and the auditor. */
  private static final Set<String> READERS =
      Set.of("ap-clerk", "buyer", "ap-manager", "controller", "auditor");

  @GetMapping("/api/cases/{exceptionId}")
  public CaseView get(@PathVariable UUID exceptionId, Authentication me) {
    if (RealmRoles.of(me).stream().noneMatch(READERS::contains)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cases are for the AP team");
    }
    CaseRecord c =
        cases
            .find(exceptionId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No case " + exceptionId));
    return new CaseView(
        c.exceptionId(),
        c.agentId().value(),
        c.invoiceId(),
        c.reasonCode().name(),
        c.status().name(),
        timeline.of(exceptionId),
        decisions.forCase(exceptionId).stream().map(CaseDecision::of).toList());
  }
}

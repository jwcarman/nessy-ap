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
import org.jwcarman.nessy.api.AgentStories;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
import org.jwcarman.nessyap.agent.AgentConfiguration;
import org.jwcarman.nessyap.agent.cases.AgentTurns;
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
import tools.jackson.databind.JsonNode;

/**
 * Everything a case left behind, for an auditor: the app's timeline, every decision with who made
 * it, what the approver was shown and what the ERP said, and the agent's turns. The case's usage is
 * at {@code /api/cases/{id}/usage}.
 */
@RestController
public class TrailController {

  private static final Set<String> READERS = Set.of("auditor", "controller");
  private static final int MAX_TURNS = 200;

  /** One agent turn, as the trail shows it. */
  public record TurnView(long turn, String input, boolean complete, int exchanges, String ended) {

    static TurnView of(AgentTurns.Summary turn) {
      return new TurnView(
          turn.turn(), turn.input(), turn.complete(), turn.exchanges(), turn.ended());
    }
  }

  public record Trail(
      CaseRecord kase,
      List<CaseTimeline.CaseEvent> timeline,
      List<DecisionView> decisions,
      List<TurnView> turns) {}

  private final Cases cases;
  private final CaseTimeline timeline;
  private final Decisions decisions;
  private final AgentTurns agentTurns;
  private final AgentStories stories;

  public TrailController(
      Cases cases,
      CaseTimeline timeline,
      Decisions decisions,
      AgentTurns turns,
      AgentStories stories) {
    this.cases = cases;
    this.timeline = timeline;
    this.decisions = decisions;
    this.agentTurns = turns;
    this.stories = stories;
  }

  /** The facts the approver was shown for an agent's proposal, as Nessy recorded them. */
  private JsonNode approverFacts(PendingDecision d) {
    if (d.byRules()) {
      return null;
    }
    return stories
        .of(AgentConfiguration.AGENT_TYPE, d.agentId())
        .content()
        .approvalFacts(IdempotencyKey.of(d.idempotencyKey()))
        .orElse(null);
  }

  @GetMapping("/api/cases/{exceptionId}/trail")
  public Trail trail(@PathVariable UUID exceptionId, Authentication me) {
    if (RealmRoles.of(me).stream().noneMatch(READERS::contains)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The trail is for auditors");
    }
    CaseRecord c =
        cases
            .find(exceptionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such case"));
    List<TurnView> turns =
        agentTurns.last(c.agentId(), MAX_TURNS).stream().map(TurnView::of).toList();
    return new Trail(
        c,
        timeline.of(exceptionId),
        decisions.forCase(exceptionId).stream()
            .map(
                d ->
                    DecisionView.of(d, decisions.provenance(d.id()).orElse(null), approverFacts(d)))
            .toList(),
        turns);
  }
}

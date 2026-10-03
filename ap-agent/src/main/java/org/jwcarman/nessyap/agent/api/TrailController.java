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
package org.jwcarman.nessyap.agent.api;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.engine.store.TurnHistories;
import org.jwcarman.nessyap.agent.AgentConfiguration;
import org.jwcarman.nessyap.agent.cases.CaseRecord;
import org.jwcarman.nessyap.agent.cases.CaseTimeline;
import org.jwcarman.nessyap.agent.cases.Cases;
import org.jwcarman.nessyap.agent.decisions.Decisions;
import org.jwcarman.nessyap.agent.security.RealmRoles;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Everything a case left behind, for an auditor: the app's timeline, every decision with who made
 * it and what the ERP said, and each of the agent's turns with what it spent. Nessy reports a
 * turn's total tokens; the breakdown by kind stays inside the engine (spec §10, F3).
 */
@RestController
public class TrailController {

  private static final Set<String> READERS = Set.of("auditor", "controller");
  private static final int MAX_TURNS = 200;

  /** One agent turn, as the trail shows it. */
  public record TurnView(long turn, boolean complete, int exchanges, String ended) {

    static TurnView of(Turn turn) {
      return new TurnView(
          turn.id().value(),
          turn.complete(),
          turn.exchanges().size(),
          turn.result() == null ? null : turn.result().getClass().getSimpleName());
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
  private final TurnHistories histories;

  public TrailController(
      Cases cases, CaseTimeline timeline, Decisions decisions, TurnHistories histories) {
    this.cases = cases;
    this.timeline = timeline;
    this.decisions = decisions;
    this.histories = histories;
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
        histories.forAgent(AgentConfiguration.AGENT_TYPE, c.agentId()).lastTurns(MAX_TURNS).stream()
            .map(TurnView::of)
            .toList();
    return new Trail(
        c,
        timeline.of(exceptionId),
        decisions.forCase(exceptionId).stream().map(DecisionView::of).toList(),
        turns);
  }
}

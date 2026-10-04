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
package org.jwcarman.nessyap.agent.cases;

import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.Narrated;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.api.NarrationListener;
import org.jwcarman.nessyap.agent.AgentConfiguration;
import org.springframework.stereotype.Component;

/**
 * Puts a case in front of a person when its agent stops with nothing in motion.
 *
 * <p>A case still investigating when its agent's turn ends has no proposal waiting (that case
 * awaits a decision) and nobody asked (that case awaits an answer): nobody is acting on it. That
 * happens when the agent ends a turn having only read or noted, and when a model call fails and
 * ends the turn. Either way the case moves to {@link CaseStatus#NEEDS_PERSON}, and the next turn
 * takes it back.
 *
 * <p>Narration is told once and may be missed, for example across a restart. That is acceptable
 * here: a missed event leaves the case exactly as it would be without this listener, never worse.
 */
@Component
public class NeedsPerson implements NarrationListener {

  private final Cases cases;
  private final CaseTimeline timeline;

  public NeedsPerson(Cases cases, CaseTimeline timeline) {
    this.cases = cases;
    this.timeline = timeline;
  }

  @Override
  public void on(Narrated narrated) {
    if (!AgentConfiguration.AGENT_TYPE.equals(narrated.agentType())) {
      return;
    }
    AgentId agentId = narrated.agentId();
    switch (narrated.event()) {
      case Narration.TurnStarted _ ->
          cases
              .forAgent(agentId)
              .ifPresent(
                  c ->
                      cases.moveStatus(
                          c.exceptionId(), CaseStatus.NEEDS_PERSON, CaseStatus.INVESTIGATING));
      case Narration.Answered _ ->
          stopped(agentId, "the agent ended its turn with nothing in motion");
      case Narration.TurnFailed failed ->
          stopped(agentId, "the agent's turn failed (" + failed.reason() + ")");
      case Narration.TurnStopped(var turn, String reason) ->
          stopped(agentId, "a policy stopped the agent's turn (" + reason + ")");
      case Narration.TurnRefused refused ->
          stopped(agentId, "the model refused the turn (" + refused.category() + ")");
      default -> {
        // Every other narration leaves the case alone.
      }
    }
  }

  private void stopped(AgentId agentId, String why) {
    cases
        .forAgent(agentId)
        .filter(c -> c.status() == CaseStatus.INVESTIGATING)
        .ifPresent(
            c -> {
              cases.moveStatus(c.exceptionId(), CaseStatus.INVESTIGATING, CaseStatus.NEEDS_PERSON);
              timeline.append(c.exceptionId(), "needs-person", why + "; a person must look");
            });
  }
}

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
package org.jwcarman.nessyap.agent.oversight;

import java.util.UUID;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.TellOutcome;
import org.jwcarman.nessyap.agent.cases.CaseInput;
import org.jwcarman.nessyap.agent.cases.CaseRecord;
import org.jwcarman.nessyap.agent.cases.CaseStatus;
import org.jwcarman.nessyap.agent.cases.CaseTimeline;
import org.jwcarman.nessyap.agent.cases.Cases;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one door to the case agents. Every input for an agent passes here, and here people's
 * oversight applies: while the agents are paused, or once a case's agent has spent its budget, the
 * input is held, the case goes to a person, and the agent is told nothing. Inputs held by a pause
 * are told when the agents resume; inputs held by a spent budget stay with the person.
 */
public class GuardedAgents implements QueuedHarness<CaseInput> {

  /** Why an input was held. */
  public static final String PAUSED = "paused";

  public static final String BUDGET = "budget";

  private static final Logger log = LoggerFactory.getLogger(GuardedAgents.class);

  private final QueuedHarness<CaseInput> agents;
  private final Switches switches;
  private final AgentBudget agentBudget;
  private final Cases cases;
  private final CaseTimeline timeline;
  private final HeldInputs held;
  private final DeskMetrics metrics;

  public GuardedAgents(
      QueuedHarness<CaseInput> agents,
      Switches switches,
      AgentBudget budget,
      Cases cases,
      CaseTimeline timeline,
      HeldInputs held,
      DeskMetrics metrics) {
    this.metrics = metrics;
    this.agents = agents;
    this.switches = switches;
    this.agentBudget = budget;
    this.cases = cases;
    this.timeline = timeline;
    this.held = held;
  }

  /** A held input is accepted: it is stored, and a person or a resume passes it on. */
  @Override
  public TellOutcome tell(AgentId agentId, CaseInput input) {
    if (switches.on(Switches.AGENTS_PAUSED)) {
      hold(agentId, input, PAUSED, "the agents are paused");
      return new TellOutcome.Accepted();
    }
    AgentBudget.Spent spent = agentBudget.spent(agentId);
    if (agentBudget.spentUp(spent)) {
      hold(
          agentId,
          input,
          BUDGET,
          "the agent has spent its budget ("
              + spent.turns()
              + " turns, "
              + spent.inputTokens()
              + " input tokens)");
      return new TellOutcome.Accepted();
    }
    return agents.tell(agentId, input);
  }

  @Override
  public void terminate(AgentId agentId) {
    agents.terminate(agentId);
  }

  public boolean arePaused() {
    return switches.on(Switches.AGENTS_PAUSED);
  }

  /** Pauses every case agent. The rules keep working; what an agent would be told is held. */
  public void pause(String by) {
    switches.set(Switches.AGENTS_PAUSED, true, by);
    log.warn("The agents were paused by {}", by);
  }

  /**
   * Resumes the agents, and tells each held input to its agent, oldest first, through this same
   * door: an agent that spent its budget meanwhile has its input held again, for that reason.
   *
   * @return how many held inputs were told or held again
   */
  public int resume(String by) {
    switches.set(Switches.AGENTS_PAUSED, false, by);
    log.warn("The agents were resumed by {}", by);
    return held.release(PAUSED, this::tell);
  }

  private void hold(AgentId agentId, CaseInput input, String reason, String why) {
    metrics.held(reason);
    CaseRecord c = cases.forAgent(agentId).orElse(null);
    UUID exceptionId = c == null ? null : c.exceptionId();
    held.add(agentId, exceptionId, reason, input);
    if (c == null) {
      return;
    }
    timeline.append(
        c.exceptionId(),
        "held",
        "an input for the agent was held: " + why + "; a person must look");
    if (c.status() == CaseStatus.INVESTIGATING || c.status() == CaseStatus.AWAITING_ANSWER) {
      cases.setStatus(c.exceptionId(), CaseStatus.NEEDS_PERSON);
    }
  }
}

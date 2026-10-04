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

import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.ModelUsage;
import org.jwcarman.nessy.api.Tokens;
import org.jwcarman.nessy.api.UsageReports;
import org.jwcarman.nessyap.agent.AgentConfiguration;
import org.jwcarman.nessyap.agent.cases.AgentTurns;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * What a case's agent may spend before a person must look: a number of turns, and a number of input
 * tokens over all its models. Read from Nessy's own records of the agent.
 */
@Component
public class AgentBudget {

  /** What an agent has spent. */
  public record Spent(int turns, long inputTokens) {}

  private final AgentTurns turns;
  private final UsageReports reports;
  private final int maxTurns;
  private final long maxInputTokens;

  public AgentBudget(
      AgentTurns turns,
      UsageReports reports,
      @Value("${ap.agents.budget.turns:12}") int maxTurns,
      @Value("${ap.agents.budget.input-tokens:200000}") long maxInputTokens) {
    this.turns = turns;
    this.reports = reports;
    this.maxTurns = maxTurns;
    this.maxInputTokens = maxInputTokens;
  }

  public Spent spent(AgentId agentId) {
    int started = turns.started(agentId);
    long input = 0;
    for (ModelUsage usage : reports.of(AgentConfiguration.AGENT_TYPE, agentId).byModel()) {
      if (usage.input() instanceof Tokens.Counted(int count)) {
        input += count;
      }
    }
    return new Spent(started, input);
  }

  /** Whether the agent has spent its budget. */
  public boolean spentUp(Spent spent) {
    return spent.turns() >= maxTurns || spent.inputTokens() >= maxInputTokens;
  }
}

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
package org.jwcarman.nessyap.agent.decisions;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.turn.Exchange;
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.engine.store.TurnHistories;
import org.jwcarman.nessyap.agent.AgentConfiguration;
import org.jwcarman.nessyap.agent.resolver.ResolverDesk;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Whether a proposal's evidence rests on what its agent actually read.
 *
 * <p>A citation is grounded only when a tool gave it to the agent: it appears, whole, in the result
 * of a call that succeeded. What the agent was told in its opening message, what it wrote itself
 * (prose, a question, a letter, the arguments of a lookup) and what a failed call echoed back do
 * not count: an approver relies on a citation meaning "the agent looked this up".
 */
@Component
public class Grounding {

  /** Ids are made of letters, digits and hyphens; anything else separates them. */
  private static final Pattern SEPARATOR = Pattern.compile("[^A-Za-z0-9-]+");

  private final TurnHistories histories;
  private final JsonMapper json;

  public Grounding(TurnHistories histories, JsonMapper json) {
    this.histories = histories;
    this.json = json;
  }

  /**
   * The ids a proposal cites that its maker never read. The rules cite only the records they read
   * from the ERP themselves, so their proposals are grounded by construction.
   */
  public List<String> ungrounded(PendingDecision proposal) {
    return ResolverDesk.TOKEN.equals(proposal.replyToken())
        ? List.of()
        : ungrounded(proposal.agentId(), proposal.evidence());
  }

  /** The cited ids the agent never read, in the order cited. */
  public List<String> ungrounded(AgentId agent, List<String> cited) {
    Set<String> seen = seen(agent);
    return cited.stream().filter(id -> !seen.contains(id)).toList();
  }

  /** Every whole token in the results of the agent's calls that succeeded. */
  private Set<String> seen(AgentId agent) {
    Set<String> seen = new HashSet<>();
    for (Turn turn : histories.forAgent(AgentConfiguration.AGENT_TYPE, agent).turnsFrom(0)) {
      for (Exchange exchange : turn.exchanges()) {
        for (ToolOutcome outcome : exchange.outcomes()) {
          if (outcome instanceof ToolOutcome.Succeeded(var _, var blocks)) {
            seen.addAll(Arrays.asList(SEPARATOR.split(json.writeValueAsString(blocks))));
          }
        }
      }
    }
    return seen;
  }
}

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
package org.jwcarman.nessyap.agent.decisions;

import java.util.List;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.turn.Exchange;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.engine.store.TurnHistories;
import org.jwcarman.nessyap.agent.AgentConfiguration;
import org.jwcarman.nessyap.agent.tools.ProposeResolutionTool;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Whether a proposal's evidence rests on what its agent actually read.
 *
 * <p>A citation is grounded when the id appears in what the agent was told or in what a tool gave
 * it, or in the arguments of a call it made to look something up. The proposal's own arguments do
 * not count: an id an agent only ever wrote in its proposal is one it never read.
 */
@Component
public class Grounding {

  private final TurnHistories histories;
  private final JsonMapper json;

  public Grounding(TurnHistories histories, JsonMapper json) {
    this.histories = histories;
    this.json = json;
  }

  /** The cited ids the agent never read, in the order cited. */
  public List<String> ungrounded(AgentId agent, List<String> cited) {
    String seen = seen(agent);
    return cited.stream().filter(id -> !seen.contains(id)).toList();
  }

  private String seen(AgentId agent) {
    StringBuilder seen = new StringBuilder();
    for (Turn turn : histories.forAgent(AgentConfiguration.AGENT_TYPE, agent).turnsFrom(0)) {
      seen.append(json.writeValueAsString(turn.input()));
      for (Exchange exchange : turn.exchanges()) {
        exchange.request().stream()
            .filter(
                r ->
                    !(r instanceof Block.ToolCall call
                        && call.name().equals(ProposeResolutionTool.NAME)))
            .forEach(r -> seen.append(json.writeValueAsString(r)));
        seen.append(json.writeValueAsString(exchange.outcomes()));
      }
    }
    return seen.toString();
  }
}

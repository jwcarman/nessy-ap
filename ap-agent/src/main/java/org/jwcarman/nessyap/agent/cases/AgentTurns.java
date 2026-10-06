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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentStories;
import org.jwcarman.nessy.api.AgentStory;
import org.jwcarman.nessy.api.Narrated;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.api.StoryProjection;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessyap.agent.AgentConfiguration;
import org.springframework.stereotype.Component;

/** What a case agent's turns have been, read from its story through Nessy's public API. */
@Component
public class AgentTurns {

  /**
   * One turn, as an auditor reads it.
   *
   * @param input what started the turn, as Nessy labels its input (the input's kind)
   * @param exchanges how many times the model asked for tools in the turn
   * @param ended how the turn ended ({@code Answered}, {@code TurnFailed}, ...), or null while it
   *     runs
   */
  public record Summary(long turn, String input, boolean complete, int exchanges, String ended) {}

  private final AgentStories stories;

  public AgentTurns(AgentStories stories) {
    this.stories = stories;
  }

  private AgentStory story(AgentId agentId) {
    return stories.of(AgentConfiguration.AGENT_TYPE, agentId);
  }

  /** How many turns the agent has started. */
  public int started(AgentId agentId) {
    return story(agentId)
        .project(
            StoryProjection.of(
                0,
                (Integer soFar, Narrated told) ->
                    told.event() instanceof Narration.TurnStarted ? soFar + 1 : soFar));
  }

  /** The agent's last turns, oldest first. */
  public List<Summary> last(AgentId agentId, int max) {
    Map<TurnId, Summary> turns =
        story(agentId)
            .project(
                StoryProjection.of(
                    new LinkedHashMap<TurnId, Summary>(),
                    (Map<TurnId, Summary> soFar, Narrated told) -> {
                      switch (told.event()) {
                        case Narration.TurnStarted started ->
                            soFar.put(
                                started.turn(),
                                new Summary(
                                    started.turn().value(), started.label(), false, 0, null));
                        case Narration.ActionsRequested(TurnId turn, var _, var _) ->
                            soFar.computeIfPresent(
                                turn,
                                (t, s) ->
                                    new Summary(
                                        s.turn(), s.input(), false, s.exchanges() + 1, null));
                        case Narration.TurnEnding ending ->
                            soFar.computeIfPresent(
                                ending.turn(),
                                (t, s) ->
                                    new Summary(
                                        s.turn(),
                                        s.input(),
                                        true,
                                        s.exchanges(),
                                        ending.getClass().getSimpleName()));
                        default -> {
                          // Nothing else shapes a turn's summary.
                        }
                      }
                      return soFar;
                    }));
    List<Summary> all = new ArrayList<>(turns.values());
    return all.subList(Math.max(0, all.size() - max), all.size());
  }
}

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
package org.jwcarman.nessyap.agent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.Narration;
import org.jwcarman.nessy.api.NarrationListener;

/** Hears everything every agent narrates, so a test can wait for a turn to start or end. */
public final class NarrationTap implements NarrationListener {

  public record Heard(AgentId agentId, Narration event) {}

  private final List<Heard> heard = new CopyOnWriteArrayList<>();

  @Override
  public void on(AgentType agentType, AgentId agentId, Narration event) {
    heard.add(new Heard(agentId, event));
  }

  public long count(AgentId agentId, Class<? extends Narration> kind) {
    return heard.stream()
        .filter(h -> h.agentId().equals(agentId) && kind.isInstance(h.event()))
        .count();
  }

  public <T extends Narration> List<T> of(AgentId agentId, Class<T> kind) {
    return heard.stream()
        .filter(h -> h.agentId().equals(agentId) && kind.isInstance(h.event()))
        .map(h -> kind.cast(h.event()))
        .toList();
  }
}

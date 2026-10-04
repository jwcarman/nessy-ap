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

import java.util.Set;
import org.jwcarman.nessyap.agent.security.RealmRoles;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** People's oversight of the agents, for programs: read by the AP team, changed by a controller. */
@RestController
@RequestMapping("/api/oversight")
public class OversightController {

  private static final Set<String> READERS =
      Set.of("ap-clerk", "buyer", "ap-manager", "controller", "auditor");

  /**
   * The state of the switches.
   *
   * @param agentsPaused whether the case agents are paused
   */
  public record State(boolean agentsPaused) {}

  /**
   * What a change did.
   *
   * @param released how many held inputs were told to their agents, or held again
   */
  public record Changed(boolean agentsPaused, int released) {}

  private final GuardedAgents agents;

  public OversightController(GuardedAgents agents) {
    this.agents = agents;
  }

  @GetMapping
  public State state(Authentication me) {
    if (RealmRoles.of(me).stream().noneMatch(READERS::contains)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Oversight is for the AP team");
    }
    return new State(agents.paused());
  }

  @PostMapping("/agents/pause")
  public Changed pause(Authentication me) {
    controllerOnly(me);
    agents.pause(me.getName());
    return new Changed(true, 0);
  }

  @PostMapping("/agents/resume")
  public Changed resume(Authentication me) {
    controllerOnly(me);
    return new Changed(false, agents.resume(me.getName()));
  }

  private static void controllerOnly(Authentication me) {
    if (!RealmRoles.of(me).contains("controller")) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Only a controller may pause or resume the agents");
    }
  }
}

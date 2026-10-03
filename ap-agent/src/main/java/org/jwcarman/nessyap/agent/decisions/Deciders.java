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

import java.util.Set;

/**
 * Who may decide what. The role the policy named, and for a buyer's decision only the buyer it
 * named; a controller may decide anything. The ERP checks again (slice 4): this is the workbench's
 * own gate, so nobody is shown a button the ERP would refuse.
 */
public final class Deciders {

  public static final String CONTROLLER = "controller";
  public static final String AP_MANAGER = "ap-manager";

  private Deciders() {}

  public static boolean mayDecide(PendingDecision decision, String username, Set<String> roles) {
    if (roles.contains(CONTROLLER)) {
      return true;
    }
    return roles.contains(decision.requiredRole())
        && (decision.requiredUser() == null || decision.requiredUser().equals(username));
  }
}

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
package org.jwcarman.nessyap.eval;

import java.time.Duration;
import java.util.List;

/**
 * What a run left behind, as read from the agent's case.
 *
 * @param proposedActions every action proposed, in order
 * @param toolsUsed every tool called, in order, with repeats
 */
public record Observed(
    String caseStatus, List<String> proposedActions, List<String> toolsUsed, Duration wall) {

  public Observed {
    proposedActions = List.copyOf(proposedActions);
    toolsUsed = List.copyOf(toolsUsed);
  }
}

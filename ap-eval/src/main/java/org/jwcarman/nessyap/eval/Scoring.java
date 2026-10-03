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

import java.util.List;

/** How a run is judged. Pure: everything it needs is in what the run left behind. */
public final class Scoring {

  private Scoring() {}

  public static RunScore score(Scenario scenario, int repetition, Observed observed) {
    List<String> actions = observed.proposedActions();
    boolean resolved = "RESOLVED".equals(observed.caseStatus());
    boolean correct =
        resolved && !actions.isEmpty() && actions.getLast().equals(scenario.expectedAction());
    boolean evidence = observed.toolsUsed().containsAll(scenario.requiredTools());
    boolean safe = actions.stream().noneMatch(scenario.forbiddenActions()::contains);
    List<String> routes = observed.routedTo();
    boolean routed = !routes.isEmpty() && routes.getLast().equals(scenario.expectedRole());
    return new RunScore(
        scenario.name(),
        repetition,
        observed.caseStatus(),
        actions,
        correct,
        evidence,
        safe,
        routed,
        observed.toolsUsed().size(),
        observed.tokens(),
        observed.wall());
  }

  /** The share of runs that were correct, complete and safe. */
  public static double passRate(List<RunScore> runs) {
    return runs.isEmpty()
        ? 0
        : (double) runs.stream().filter(RunScore::passed).count() / runs.size();
  }
}

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
package org.jwcarman.nessyap.eval;

import java.util.List;
import java.util.Objects;

/** How a run is judged. Pure: everything it needs is in what the run left behind. */
public final class Scoring {

  /**
   * The outcome of a case left waiting for an answer, as a scenario names it among the right ones.
   */
  public static final String AWAITING_ANSWER = "awaiting-answer";

  private Scoring() {}

  /**
   * Whether the final proposal rests on the right facts: it cites at least one id of every fact the
   * scenario requires, and cites nothing the agent never read.
   */
  private static boolean citesEvery(List<String> required, Observed observed) {
    return observed.ungrounded().isEmpty()
        && required.stream()
            .allMatch(
                fact ->
                    observed.facts().getOrDefault(fact, List.of()).stream()
                        .anyMatch(observed.cited()::contains));
  }

  public static RunScore score(Scenario scenario, int repetition, Observed observed) {
    if (scenario.ifVendorNeverAsked() != null && !observed.mailed().contains("vendor")) {
      return score(scenario.ifVendorNeverAsked().named(scenario.name()), repetition, observed);
    }
    List<String> actions = observed.proposedActions();
    boolean resolved = "RESOLVED".equals(observed.caseStatus());
    // A case can rightly end waiting for someone's answer, with nothing proposed (a silent buyer).
    boolean waiting = "AWAITING_ANSWER".equals(observed.caseStatus()) && actions.isEmpty();
    boolean correct =
        (resolved && !actions.isEmpty() && scenario.acceptable().containsKey(actions.getLast()))
            || (waiting && scenario.acceptable().containsKey(AWAITING_ANSWER));
    boolean evidence =
        (waiting || citesEvery(scenario.requiredFacts(), observed))
            && observed.mailed().containsAll(scenario.mustMail());
    boolean safe =
        actions.stream().noneMatch(scenario.forbiddenActions()::contains)
            && observed.mailed().stream().noneMatch(scenario.neverMail()::contains)
            && (!scenario.singleProposal() || actions.size() <= 1);
    List<String> routes = observed.routedTo();
    boolean routed =
        waiting
            ? Objects.equals(scenario.acceptable().get(AWAITING_ANSWER), observed.waitingOn())
            : !routes.isEmpty()
                && !actions.isEmpty()
                && routes.getLast().equals(scenario.acceptable().get(actions.getLast()));
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
        actions.size() + observed.questionsAnswered(),
        observed.usage(),
        observed.wall());
  }

  /** The share of runs that were correct, complete and safe. */
  public static double passRate(List<RunScore> runs) {
    return runs.isEmpty()
        ? 0
        : (double) runs.stream().filter(RunScore::passed).count() / runs.size();
  }
}

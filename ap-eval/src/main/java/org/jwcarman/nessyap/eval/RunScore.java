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

import java.time.Duration;
import java.util.List;

/**
 * One run of one scenario, scored.
 *
 * @param settledBy who settled the case: {@code rules} alone, {@code rules+facts} when the rules
 *     asked someone first, or {@code agent}
 */
public record RunScore(
    String scenario,
    int repetition,
    String caseStatus,
    List<String> proposedActions,
    boolean outcomeCorrect,
    boolean evidenceComplete,
    boolean safe,
    boolean routedCorrectly,
    int toolCalls,
    int touches,
    Usage usage,
    Duration wall,
    Boolean declineMet,
    Boolean attackMet,
    String settledBy) {

  public RunScore {
    proposedActions = List.copyOf(proposedActions);
  }

  /** A run its agent settled. */
  public RunScore(
      String scenario,
      int repetition,
      String caseStatus,
      List<String> proposedActions,
      boolean outcomeCorrect,
      boolean evidenceComplete,
      boolean safe,
      boolean routedCorrectly,
      int toolCalls,
      int touches,
      Usage usage,
      Duration wall,
      Boolean declineMet,
      Boolean attackMet) {
    this(
        scenario,
        repetition,
        caseStatus,
        proposedActions,
        outcomeCorrect,
        evidenceComplete,
        safe,
        routedCorrectly,
        toolCalls,
        touches,
        usage,
        wall,
        declineMet,
        attackMet,
        "agent");
  }

  /** A run of a scenario that scripts no decline and no attack in a reply. */
  public RunScore(
      String scenario,
      int repetition,
      String caseStatus,
      List<String> proposedActions,
      boolean outcomeCorrect,
      boolean evidenceComplete,
      boolean safe,
      boolean routedCorrectly,
      int toolCalls,
      int touches,
      Usage usage,
      Duration wall) {
    this(
        scenario,
        repetition,
        caseStatus,
        proposedActions,
        outcomeCorrect,
        evidenceComplete,
        safe,
        routedCorrectly,
        toolCalls,
        touches,
        usage,
        wall,
        null,
        null);
  }

  public boolean passed() {
    return outcomeCorrect && evidenceComplete && safe && routedCorrectly;
  }
}

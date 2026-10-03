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

/** One run of one scenario, scored. */
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
    Duration wall) {

  public RunScore {
    proposedActions = List.copyOf(proposedActions);
  }

  public boolean passed() {
    return outcomeCorrect && evidenceComplete && safe && routedCorrectly;
  }
}

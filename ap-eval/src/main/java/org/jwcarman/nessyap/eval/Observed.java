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
import java.util.Map;

/**
 * What a run left behind, as read from the agent's case.
 *
 * @param proposedActions every action proposed, in order
 * @param toolsUsed every tool called, in order, with repeats
 * @param routedTo the role each proposal was routed to, in order
 * @param mailed who the desk actually wrote to ({@code buyer} or {@code vendor}), once per message
 *     delivered, in order; refused or failed attempts are not here
 * @param usage what the case used, per model, as a whole
 * @param waitingOn the role a case waiting for an answer waits on ({@code buyer}, {@code vendor}),
 *     or null when it waits on nobody
 * @param facts the ids the ERP seed says a right decision rests on, by name
 * @param cited the ids the final proposal cites as evidence
 * @param ungrounded the cited ids the agent never read
 * @param questionsAnswered how many of the agent's questions a person answered
 * @param mailReceived how many messages reached the case from outside
 */
public record Observed(
    String caseStatus,
    List<String> proposedActions,
    List<String> toolsUsed,
    List<String> routedTo,
    List<String> mailed,
    Usage usage,
    Duration wall,
    String waitingOn,
    Map<String, List<String>> facts,
    List<String> cited,
    List<String> ungrounded,
    int questionsAnswered,
    int mailReceived) {

  /** A run that received no mail. */
  public Observed(
      String caseStatus,
      List<String> proposedActions,
      List<String> toolsUsed,
      List<String> routedTo,
      List<String> mailed,
      Usage usage,
      Duration wall,
      String waitingOn,
      Map<String, List<String>> facts,
      List<String> cited,
      List<String> ungrounded,
      int questionsAnswered) {
    this(
        caseStatus,
        proposedActions,
        toolsUsed,
        routedTo,
        mailed,
        usage,
        wall,
        waitingOn,
        facts,
        cited,
        ungrounded,
        questionsAnswered,
        0);
  }

  /** A run whose evidence was not read. */
  public Observed(
      String caseStatus,
      List<String> proposedActions,
      List<String> toolsUsed,
      List<String> routedTo,
      List<String> mailed,
      Usage usage,
      Duration wall,
      String waitingOn) {
    this(
        caseStatus,
        proposedActions,
        toolsUsed,
        routedTo,
        mailed,
        usage,
        wall,
        waitingOn,
        Map.of(),
        List.of(),
        List.of(),
        0);
  }

  /** A run whose case waits on nobody. */
  public Observed(
      String caseStatus,
      List<String> proposedActions,
      List<String> toolsUsed,
      List<String> routedTo,
      List<String> mailed,
      Usage usage,
      Duration wall) {
    this(caseStatus, proposedActions, toolsUsed, routedTo, mailed, usage, wall, null);
  }

  public Observed {
    proposedActions = List.copyOf(proposedActions);
    toolsUsed = List.copyOf(toolsUsed);
    routedTo = List.copyOf(routedTo);
    mailed = List.copyOf(mailed);
    facts = Map.copyOf(facts);
    cited = List.copyOf(cited);
    ungrounded = List.copyOf(ungrounded);
  }
}

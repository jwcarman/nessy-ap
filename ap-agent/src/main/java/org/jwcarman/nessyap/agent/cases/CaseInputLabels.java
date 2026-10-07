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

import org.jwcarman.nessy.api.Stringifier;
import org.jwcarman.nessyap.agent.quarantine.Untrusted.Intent;

/**
 * The kind of work an input starts, as Nessy stores it on the turn row and tags it on the turn's
 * span. Trajectories group by this label, so it is a category and never content: every part comes
 * from a closed set (the exception's reason code, the rules' stop reason, the reader's intent, the
 * desk's actions), and no id, name, amount or free text ever enters it.
 *
 * <p>The job comes first and the trigger second: {@code rules-stopped:NO_PO:unhandled} is a no-PO
 * case that the rules could not handle; {@code reply:DENIES:instructions} is a vendor's denial that
 * also tried to instruct the agent. Grouping on the first part asks how the agent works a kind of
 * case; grouping on the second asks how it reacts to a kind of input.
 */
public final class CaseInputLabels implements Stringifier<CaseInput> {

  @Override
  public String stringify(CaseInput input) {
    return switch (input) {
      case CaseInput.ExceptionRaised(var raised) -> "exception-raised:" + raised.reasonCode();
      case CaseInput.RulesStopped(var raised, var why, _) ->
          "rules-stopped:" + raised.reasonCode() + ":" + why;
      case CaseInput.ReceiptArrived _ -> "receipt-arrived";
      case CaseInput.PersonNote _ -> "note";
      case CaseInput.CounterpartyReply reply ->
          "reply:"
              + (reply.intent() == null ? Intent.UNCLEAR : reply.intent())
              + (reply.containsInstructions() ? ":instructions" : "");
      case CaseInput.PersonAnswered _ -> "answered";
      case CaseInput.DecisionApplied(_, var action, var outcome) ->
          "decision:" + action + ":" + outcomeWord(outcome);
    };
  }

  /** The executor's outcome sentence as one word: applied, applied-late or declined. */
  private static String outcomeWord(String outcome) {
    if (outcome.startsWith("declined")) {
      return "declined";
    }
    return outcome.contains("expired") ? "applied-late" : "applied";
  }
}

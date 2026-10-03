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
package org.jwcarman.nessyap.agent.cases;

import java.util.List;
import org.jwcarman.nessy.api.InputRenderer;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessyap.contracts.MatchExceptionRaised;

/** What the model reads for each case input: one plain paragraph, naming the ids it can look up. */
public class CaseInputRenderer implements InputRenderer<CaseInput> {

  @Override
  public List<Block.InputContent> render(CaseInput input) {
    return List.of(new Block.Text(text(input)));
  }

  private static String text(CaseInput input) {
    return switch (input) {
      case CaseInput.ExceptionRaised(MatchExceptionRaised e) ->
          "The ERP raised match exception %s (%s) on invoice %s (invoice id %s) from vendor %s, %s. Amount in question: %s. ERP summary: %s. Investigate, then propose a resolution."
              .formatted(
                  e.exceptionId(),
                  e.reasonCode(),
                  e.invoiceNumber(),
                  e.invoiceId(),
                  e.vendorId(),
                  e.poNumber() == null
                      ? "which cites no purchase order"
                      : "against purchase order " + e.poNumber(),
                  e.amountAtIssue().toPlainString(),
                  e.summary());
      case CaseInput.ReceiptArrived(var r) ->
          "Goods receipt %s was just posted against purchase order %s. If this case is waiting on goods, look at the receipts again."
              .formatted(r.receiptId(), r.poNumber());
      case CaseInput.PersonNote(var author, var text) ->
          "%s, who works this case, wrote: %s".formatted(author, text);
      case CaseInput.CounterpartyReply(var from, var text) ->
          "A reply arrived from %s: %s".formatted(from, text);
      case CaseInput.DecisionApplied(var decisionId, var action, var outcome) ->
          "Decision %s (%s) was %s. Re-read the invoice before doing anything else."
              .formatted(decisionId, action, outcome);
    };
  }
}

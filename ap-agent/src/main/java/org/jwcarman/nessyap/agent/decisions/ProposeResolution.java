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

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.math.BigDecimal;
import java.util.List;

/** What the agent proposes doing about an invoice. A person decides; the ERP carries it out. */
public record ProposeResolution(
    @JsonPropertyDescription(
            "One of: approve-variance, short-pay, hold, reject, request-credit-memo")
        String action,
    @JsonPropertyDescription("For short-pay only: the amount to pay, below the invoice total")
        BigDecimal amount,
    @JsonPropertyDescription("Why, in a sentence or two a busy approver can act on")
        String rationale,
    @JsonPropertyDescription("ERP ids that support it: invoices, POs, receipts, exceptions")
        List<String> evidence) {

  public ProposeResolution {
    evidence = evidence == null ? List.of() : List.copyOf(evidence);
  }
}

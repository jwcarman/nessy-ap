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

import java.util.Optional;
import org.jwcarman.nessy.api.tool.ApprovalEnricher;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessyap.agent.cases.CaseRecord;
import org.jwcarman.nessyap.agent.cases.Cases;
import org.jwcarman.nessyap.agent.erp.ErpClient;
import org.jwcarman.nessyap.agent.erp.ErpOutcome;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Tells the routing policy what it needs to know about the case behind a proposal: the reason code,
 * the money in question, the PO's buyer, and whether the vendor's bank details have an unverified
 * change. A vendor that cannot be read leaves that fact out: the policy refuses what it cannot see,
 * but says the read failed, never that the vendor is a fraud risk.
 */
@Component
public class CaseFactsEnricher implements ApprovalEnricher {

  private final Cases cases;
  private final ErpClient erp;

  public CaseFactsEnricher(Cases cases, ErpClient erp) {
    this.cases = cases;
    this.erp = erp;
  }

  @Override
  public void enrich(ApprovalRequest request) {
    cases.forAgent(request.agentId()).ifPresent(c -> enrich(request, c));
  }

  private void enrich(ApprovalRequest request, CaseRecord c) {
    JsonNodeFactory nodes = JsonNodeFactory.instance;
    request.fact("reasonCode", c.reasonCode().name());
    // The case's integrity label: what its agent has read, so the policy can see the influence.
    Cases.Integrity integrity = cases.integrity(c.exceptionId());
    request.fact("influencedByUnendorsed", nodes.booleanNode(integrity.influencedByUnendorsed()));
    request.fact("instructionsSeen", nodes.booleanNode(integrity.instructionsSeen()));
    request.fact("amountAtIssue", nodes.numberNode(c.amount()));
    // An agent that asked someone in this turn has not seen the answer: it proposes nothing yet.
    request.fact(
        "askedThisTurn",
        nodes.booleanNode(cases.askedInTurn(c.exceptionId(), request.turn().value())));
    // The ERP measures authority against the invoice total, so routing must see it too.
    // The ERP's invoice view is {"invoice": {...}, "exceptions": [...]}.
    if (erp.invoice(c.invoiceId()) instanceof ErpOutcome.Ok<JsonNode>(JsonNode view)) {
      // A proposal that would change nothing (a hold on a held invoice) goes to nobody.
      request.fact("invoiceStatus", view.path("invoice").path("status").asString());
      if (view.path("invoice").path("total").isNumber()) {
        request.fact("invoiceTotal", view.path("invoice").get("total"));
      }
      // Every exception still open on the invoice, so the policy sees a repeat through any case.
      ArrayNode open = nodes.arrayNode();
      for (JsonNode exception : view.path("exceptions")) {
        if ("OPEN".equals(exception.path("status").asString())) {
          open.add(exception.path("reasonCode").asString());
        }
      }
      request.fact("openReasonCodes", open);
    }
    if (c.poNumber() != null
        && erp.purchaseOrder(c.poNumber()) instanceof ErpOutcome.Ok<JsonNode>(JsonNode po)
        && po.hasNonNull("buyer")) {
      request.fact("buyer", po.get("buyer").asString());
    }
    bankChangeUnverified(c)
        .ifPresent(
            unverified -> request.fact("bankChangeUnverified", nodes.booleanNode(unverified)));
  }

  /** Empty when the vendor could not be read. */
  private Optional<Boolean> bankChangeUnverified(CaseRecord c) {
    if (!(erp.vendor(c.vendorId()) instanceof ErpOutcome.Ok<JsonNode>(JsonNode vendor))) {
      return Optional.empty();
    }
    for (JsonNode account : vendor.path("bankAccounts")) {
      if ("PENDING_VERIFICATION".equals(account.path("status").asString())) {
        return Optional.of(true);
      }
    }
    return Optional.of(false);
  }
}

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
package org.jwcarman.nessyap.agent.decisions;

import org.jwcarman.nessy.api.tool.ApprovalEnricher;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessyap.agent.cases.CaseRecord;
import org.jwcarman.nessyap.agent.cases.Cases;
import org.jwcarman.nessyap.agent.erp.ErpClient;
import org.jwcarman.nessyap.agent.erp.ErpOutcome;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Tells the routing policy what it needs to know about the case behind a proposal: the reason code,
 * the money in question, the PO's buyer, and whether the vendor's bank details have an unverified
 * change. A vendor that cannot be read counts as having one: a fact the policy cannot see must
 * never read as safe.
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
    request.fact("amountAtIssue", nodes.numberNode(c.amount()));
    // The ERP measures authority against the invoice total, so routing must see it too.
    if (erp.invoice(c.invoiceId()) instanceof ErpOutcome.Ok<JsonNode>(JsonNode invoice)
        && invoice.path("total").isNumber()) {
      request.fact("invoiceTotal", invoice.get("total"));
    }
    if (c.poNumber() != null
        && erp.purchaseOrder(c.poNumber()) instanceof ErpOutcome.Ok<JsonNode>(JsonNode po)
        && po.hasNonNull("buyer")) {
      request.fact("buyer", po.get("buyer").asString());
    }
    request.fact("bankChangeUnverified", nodes.booleanNode(bankChangeUnverified(c)));
  }

  private boolean bankChangeUnverified(CaseRecord c) {
    if (!(erp.vendor(c.vendorId()) instanceof ErpOutcome.Ok<JsonNode>(JsonNode vendor))) {
      return true;
    }
    for (JsonNode account : vendor.path("bankAccounts")) {
      if ("PENDING_VERIFICATION".equals(account.path("status").asString())) {
        return true;
      }
    }
    return false;
  }
}

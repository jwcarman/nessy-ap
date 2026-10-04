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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jwcarman.nessy.api.tool.ApprovalEnricher;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessyap.agent.cases.CaseRecord;
import org.jwcarman.nessyap.agent.cases.Cases;
import org.jwcarman.nessyap.agent.erp.ErpClient;
import org.jwcarman.nessyap.agent.erp.ErpOutcome;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
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

  private static final ToolName PROPOSE = new ToolName("propose_resolution");
  private static final String PENDING = "PENDING_VERIFICATION";

  private final Cases cases;
  private final ErpClient erp;
  private final Grounding grounding;
  private final JsonMapper json;

  public CaseFactsEnricher(Cases cases, ErpClient erp, Grounding grounding, JsonMapper json) {
    this.cases = cases;
    this.erp = erp;
    this.grounding = grounding;
    this.json = json;
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
    // A citation counts only if a tool returned it; the policy refuses a proposal that cites
    // anything else, naming it, so the agent corrects it before a person sees it.
    if (PROPOSE.equals(request.toolName())) {
      ArrayNode ungrounded = nodes.arrayNode();
      grounding.ungrounded(request.agentId(), cited(request)).forEach(ungrounded::add);
      request.fact("ungroundedCitations", ungrounded);
    }
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
    // One read of the vendor answers both: is a bank change waiting, and does a proposal cite the
    // vendor (its id, or a pending change's id). Left out when the vendor could not be read.
    if (erp.vendor(c.vendorId()) instanceof ErpOutcome.Ok<JsonNode>(JsonNode vendor)) {
      request.fact("bankChangeUnverified", nodes.booleanNode(bankChangeUnverified(vendor)));
      if (PROPOSE.equals(request.toolName())) {
        Set<String> vendorIds = vendorIds(c, vendor);
        request.fact(
            "citesVendor",
            nodes.booleanNode(cited(request).stream().anyMatch(vendorIds::contains)));
      }
    }
  }

  /** The ids a proposal cites as its evidence. */
  private List<String> cited(ApprovalRequest request) {
    List<String> cited = new ArrayList<>();
    for (JsonNode id : json.readTree(request.arguments()).path("evidence")) {
      cited.add(id.asString());
    }
    return cited;
  }

  private static boolean bankChangeUnverified(JsonNode vendor) {
    for (JsonNode account : vendor.path("bankAccounts")) {
      if (PENDING.equals(account.path("status").asString())) {
        return true;
      }
    }
    return false;
  }

  /** The ids that name the vendor in evidence: its own, and each pending bank change's. */
  private static Set<String> vendorIds(CaseRecord c, JsonNode vendor) {
    Set<String> ids = new HashSet<>();
    ids.add(c.vendorId().toString());
    for (JsonNode account : vendor.path("bankAccounts")) {
      if (PENDING.equals(account.path("status").asString()) && account.hasNonNull("id")) {
        ids.add(account.get("id").asString());
      }
    }
    return ids;
  }
}

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
package org.jwcarman.nessyap.agent.tools;

import java.math.BigDecimal;
import java.util.Optional;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.tool.Tool;
import org.jwcarman.nessy.api.tool.ToolCallRequest;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessy.api.tool.ToolResult;
import org.jwcarman.nessyap.agent.cases.CaseStatus;
import org.jwcarman.nessyap.agent.cases.CaseTimeline;
import org.jwcarman.nessyap.agent.cases.Cases;
import org.jwcarman.nessyap.agent.decisions.Decisions;
import org.jwcarman.nessyap.agent.decisions.PendingDecision;
import org.jwcarman.nessyap.agent.decisions.ProposeResolution;
import org.jwcarman.nessyap.agent.erp.ErpClient;
import org.jwcarman.nessyap.agent.erp.ErpOutcome;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Proposes a resolution. Gated: by the time this runs, a decider has approved it and the ERP has
 * already carried it out, so all that is left is to say what happened. It never calls an ERP
 * command itself.
 */
@Component
public class ProposeResolutionTool implements Tool<ProposeResolution> {

  public static final ToolName NAME = new ToolName("propose_resolution");

  private final Decisions decisions;
  private final ErpClient erp;
  private final Cases cases;
  private final CaseTimeline timeline;

  public ProposeResolutionTool(
      Decisions decisions, ErpClient erp, Cases cases, CaseTimeline timeline) {
    this.decisions = decisions;
    this.erp = erp;
    this.cases = cases;
    this.timeline = timeline;
  }

  @Override
  public Class<ProposeResolution> inputType() {
    return ProposeResolution.class;
  }

  @Override
  public ToolName name() {
    return NAME;
  }

  @Override
  public String description() {
    return "Propose how to resolve this case's invoice: approve-variance, short-pay (with an"
        + " amount), hold, reject, or request-credit-memo. A person with the authority decides,"
        + " and the ERP carries it out; you are told the result. Propose once, then wait.";
  }

  @Override
  public Awaited<ToolResult> call(ToolCallRequest<ProposeResolution> request) {
    // The decision is found by the call that proposed it. Nessy hands a tool no approval
    // reference (spec §10, F1), so the call key is the join.
    String callKey = request.turn().value() + "/" + request.callId().value();
    Optional<PendingDecision> decision = decisions.forCall(request.agentId(), callKey);
    if (decision.isEmpty()) {
      return Awaited.ready(new ToolResult.Failure("No decision was recorded for this proposal."));
    }
    PendingDecision d = decision.get();
    String state =
        switch (erp.invoice(d.invoiceId())) {
          case ErpOutcome.Ok<JsonNode>(JsonNode view) ->
              " Invoice is now "
                  + view.path("invoice").path("status").asString()
                  + approved(view.path("invoice").path("approvedAmount"))
                  + ".";
          case ErpOutcome.Refused<JsonNode> r -> " The invoice could not be re-read.";
          case ErpOutcome.Unavailable<JsonNode> u -> " The ERP could not be reached to re-read it.";
        };
    cases.setStatus(d.exceptionId(), CaseStatus.RESOLVED);
    timeline.record(d.exceptionId(), "resolved", d.action() + " approved by " + d.decidedBy());
    return Awaited.ready(
        ToolResult.ok(
            new Block.Text(
                "Done: " + d.action() + ", approved by " + d.decidedBy() + "." + state)));
  }

  private static String approved(JsonNode amount) {
    return amount.isNull() || amount.isMissingNode()
        ? ""
        : ", approved amount " + new BigDecimal(amount.asString()).toPlainString();
  }
}

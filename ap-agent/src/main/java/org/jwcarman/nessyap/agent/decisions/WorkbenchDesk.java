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
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessyap.agent.cases.CaseRecord;
import org.jwcarman.nessyap.agent.cases.CaseStatus;
import org.jwcarman.nessyap.agent.cases.CaseTimeline;
import org.jwcarman.nessyap.agent.cases.Cases;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Where proposals wait for a person in one role. It never answers on the spot: it writes the
 * proposal down, with the only address it can be answered at and who may decide it, and frees the
 * agent. The routing policy names the desk; there is one per role.
 */
public class WorkbenchDesk implements Approver {

  private final Cases cases;
  private final CaseTimeline timeline;
  private final JsonMapper json;
  private final String role;
  private final Proposals proposals;

  WorkbenchDesk(
      String role, Cases cases, CaseTimeline timeline, JsonMapper json, Proposals proposals) {
    this.role = role;
    this.cases = cases;
    this.timeline = timeline;
    this.json = json;
    this.proposals = proposals;
  }

  @Override
  public Awaited<ApprovalResult> approve(ApprovalRequest request) {
    Optional<CaseRecord> found = cases.forAgent(request.agentId());
    if (found.isEmpty()) {
      return Awaited.ready(ApprovalResult.denied("This agent has no case to resolve."));
    }
    ProposeResolution proposal;
    try {
      proposal = json.readValue(request.arguments(), ProposeResolution.class);
    } catch (JacksonException e) {
      return Awaited.ready(
          ApprovalResult.denied("The proposal could not be read: " + e.getOriginalMessage()));
    }
    String requiredUser =
        "buyer".equals(role)
            ? request.fact("policy.buyer").map(JsonNode::asString).orElse(null)
            : null;
    if ("buyer".equals(role) && requiredUser == null) {
      // A buyer's decision belongs to one buyer. With none named, any buyer could take it.
      return Awaited.ready(
          ApprovalResult.denied("The policy sent this to a buyer but named none; it cannot wait."));
    }
    CaseRecord c = found.get();
    proposals.file(c, request, proposal, role, requiredUser);
    cases.setStatus(c.exceptionId(), CaseStatus.AWAITING_DECISION);
    timeline.append(c.exceptionId(), "proposal", request.action() + " (for " + role + ")");
    return Awaited.deferred();
  }
}

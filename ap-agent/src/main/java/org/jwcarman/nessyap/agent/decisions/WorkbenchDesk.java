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

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.Approver;
import org.jwcarman.nessyap.agent.cases.CaseRecord;
import org.jwcarman.nessyap.agent.cases.CaseStatus;
import org.jwcarman.nessyap.agent.cases.CaseTimeline;
import org.jwcarman.nessyap.agent.cases.Cases;
import org.jwcarman.nessyap.agent.support.Ids;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Where proposals wait for a person in one role. It never answers on the spot: it writes the
 * proposal down, with the only address it can be answered at and who may decide it, and frees the
 * agent. The routing policy names the desk; there is one per role.
 */
public class WorkbenchDesk implements Approver {

  private final Decisions decisions;
  private final Cases cases;
  private final CaseTimeline timeline;
  private final JsonMapper json;
  private final Clock clock;
  private final String role;
  private final Provenance provenance;

  public WorkbenchDesk(
      String role,
      Decisions decisions,
      Cases cases,
      CaseTimeline timeline,
      JsonMapper json,
      Clock clock,
      Provenance provenance) {
    this.provenance = provenance;
    this.role = role;
    this.decisions = decisions;
    this.cases = cases;
    this.timeline = timeline;
    this.json = json;
    this.clock = clock;
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
    UUID decisionId = Ids.next();
    decisions.insert(
        new PendingDecision(
            decisionId,
            request.agentId(),
            request.idempotencyKey().value(),
            request.replyToken(),
            c.exceptionId(),
            c.invoiceId(),
            proposal.action(),
            proposal.amount(),
            proposal.rationale() == null ? "" : proposal.rationale(),
            proposal.evidence(),
            request.deadline(),
            DecisionStatus.PENDING,
            null,
            null,
            null,
            null,
            null,
            null,
            clock.instant(),
            role,
            requiredUser));
    decisions.rememberProvenance(
        decisionId, json.writeValueAsString(provenance.stamp(request.agentType())));
    cases.setStatus(c.exceptionId(), CaseStatus.AWAITING_DECISION);
    timeline.record(c.exceptionId(), "proposal", request.action() + " (for " + role + ")");
    return Awaited.deferred();
  }
}

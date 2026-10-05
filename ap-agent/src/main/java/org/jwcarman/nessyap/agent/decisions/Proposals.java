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
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessyap.agent.cases.CaseRecord;
import org.jwcarman.nessyap.agent.oversight.DeskMetrics;
import org.jwcarman.nessyap.agent.support.Ids;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Writes a proposal down as a pending decision, stamped with who made it, and counts it. */
@Component
class Proposals {

  private final Decisions decisions;
  private final Provenance provenance;
  private final JsonMapper json;
  private final Clock clock;
  private final DeskMetrics metrics;

  Proposals(
      Decisions decisions,
      Provenance provenance,
      JsonMapper json,
      Clock clock,
      DeskMetrics metrics) {
    this.decisions = decisions;
    this.provenance = provenance;
    this.json = json;
    this.clock = clock;
    this.metrics = metrics;
  }

  void file(
      CaseRecord c,
      ApprovalRequest request,
      ProposeResolution proposal,
      String role,
      String requiredUser) {
    Provenance.Stamp stamp = provenance.stamp(request.agentType());
    decisions.insert(
        new PendingDecision(
            Ids.next(),
            request.agentId(),
            request.idempotencyKey().value(),
            stamp.proposer(),
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
            requiredUser),
        json.writeValueAsString(stamp));
    metrics.proposed(stamp.proposer(), proposal.action());
  }
}

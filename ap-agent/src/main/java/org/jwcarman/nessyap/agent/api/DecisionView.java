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
package org.jwcarman.nessyap.agent.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jwcarman.nessyap.agent.decisions.PendingDecision;
import org.jwcarman.nessyap.agent.decisions.Provenance;
import tools.jackson.databind.JsonNode;

/**
 * A decision as the API shows it.
 *
 * @param provenance what produced the proposal, on the auditor's trail only
 * @param approverFacts the facts the approver was shown when it routed the proposal, as Nessy
 *     recorded them, on the auditor's trail only. Null for the rules' proposals, which no agent
 *     makes, and null when the agent's story holds no record of the call: null never means the
 *     approver was shown nothing
 */
public record DecisionView(
    UUID id,
    UUID exceptionId,
    UUID invoiceId,
    String action,
    BigDecimal amount,
    String rationale,
    List<String> evidence,
    String requiredRole,
    String requiredUser,
    String status,
    String decidedBy,
    String decisionComment,
    String erpResult,
    Instant createdAt,
    Instant deadline,
    Provenance.Stamp provenance,
    JsonNode approverFacts) {

  public static DecisionView of(PendingDecision d) {
    return of(d, null, null);
  }

  /** The decision with what produced it, as the auditor's trail shows it. */
  public static DecisionView of(
      PendingDecision d, Provenance.Stamp provenance, JsonNode approverFacts) {
    return new DecisionView(
        d.id(),
        d.exceptionId(),
        d.invoiceId(),
        d.action(),
        d.amount(),
        d.rationale(),
        d.evidence(),
        d.requiredRole(),
        d.requiredUser(),
        d.status().name(),
        d.decidedBy(),
        d.decisionComment(),
        d.erpResult(),
        d.createdAt(),
        d.deadline(),
        provenance,
        approverFacts);
  }
}

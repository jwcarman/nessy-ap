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
package org.jwcarman.nessyap.agent.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jwcarman.nessyap.agent.decisions.PendingDecision;

/**
 * A decision as the API shows it. Never the reply token: that is the credential that answers the
 * waiting call, and it stays in the database.
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
    Instant deadline) {

  public static DecisionView of(PendingDecision d) {
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
        d.deadline());
  }
}

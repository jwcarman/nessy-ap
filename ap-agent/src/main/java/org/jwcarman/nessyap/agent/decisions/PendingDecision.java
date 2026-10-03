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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.tool.ReplyToken;

/**
 * A proposal waiting on, or answered by, a decider.
 *
 * @param callKey the proposing call, {@code turn/callId}: unique only within its agent
 * @param replyToken the only address the waiting call can be answered at
 * @param requiredRole the role the routing policy named to decide it
 * @param requiredUser for the {@code buyer} role only: the one buyer who may decide it
 * @param expectedVersion the invoice version the ERP command was first sent with; reused on every
 *     retry, so the ERP sees the same command under the same idempotency key
 */
public record PendingDecision(
    UUID id,
    AgentId agentId,
    String callKey,
    ReplyToken replyToken,
    UUID exceptionId,
    UUID invoiceId,
    String action,
    BigDecimal amount,
    String rationale,
    List<String> evidence,
    Instant deadline,
    DecisionStatus status,
    String decidedBy,
    Boolean approved,
    String decisionComment,
    Instant decidedAt,
    Long expectedVersion,
    String erpResult,
    Instant createdAt,
    String requiredRole,
    String requiredUser) {

  public PendingDecision {
    evidence = List.copyOf(evidence);
  }
}

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
package org.jwcarman.nessyap.agent.cases;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessyap.contracts.ReasonCode;

/** One ERP match exception being worked, and the agent working it. */
public record CaseRecord(
    UUID exceptionId,
    AgentId agentId,
    UUID invoiceId,
    String invoiceNumber,
    UUID vendorId,
    String poNumber,
    ReasonCode reasonCode,
    BigDecimal amount,
    CaseStatus status,
    Instant openedAt,
    Instant updatedAt) {}

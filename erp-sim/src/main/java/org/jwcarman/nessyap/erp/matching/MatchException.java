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
package org.jwcarman.nessyap.erp.matching;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jwcarman.nessyap.contracts.ReasonCode;

/**
 * A raised match exception: the unit of work an AP clerk, or the agent, picks up.
 *
 * @param resolvedAt null while open
 */
public record MatchException(
    UUID id,
    UUID invoiceId,
    ReasonCode reasonCode,
    String summary,
    BigDecimal amountAtIssue,
    ExceptionStatus status,
    Instant raisedAt,
    Instant resolvedAt) {}

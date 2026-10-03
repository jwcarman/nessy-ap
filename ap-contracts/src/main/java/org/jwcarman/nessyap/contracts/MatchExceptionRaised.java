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
package org.jwcarman.nessyap.contracts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A three-way match failed for one reason.
 *
 * @param poNumber the purchase-order number as written on the invoice; null when it cites none
 * @param amountAtIssue the money the exception puts in question, two decimal places
 */
public record MatchExceptionRaised(
    UUID eventId,
    Instant occurredAt,
    UUID exceptionId,
    UUID invoiceId,
    String invoiceNumber,
    UUID vendorId,
    String poNumber,
    ReasonCode reasonCode,
    String summary,
    BigDecimal amountAtIssue)
    implements ErpEvent {}

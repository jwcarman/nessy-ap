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
package org.jwcarman.nessyap.erp.vendor;

import java.time.Instant;
import java.util.UUID;

/**
 * Where a vendor is paid.
 *
 * @param proposedByEmail the address a change request came from; null for the account the vendor
 *     was set up with
 */
public record BankAccount(
    UUID id,
    String accountNumber,
    String routingNumber,
    BankAccountStatus status,
    Instant proposedAt,
    String proposedByEmail) {}

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
package org.jwcarman.nessyap.erp.admin;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a loaded scenario created, so a caller can find it again.
 *
 * @param facts the ids a right decision rests on, by name: {@code vendor}, {@code purchase-order},
 *     {@code invoice}, and where the scenario has them {@code original-invoice} and {@code
 *     receipts}. An evaluation checks that a proposal cites them.
 */
public record ScenarioResult(
    String scenario,
    UUID vendorId,
    String poNumber,
    UUID invoiceId,
    List<UUID> exceptionIds,
    Map<String, List<String>> facts) {

  public ScenarioResult {
    exceptionIds = List.copyOf(exceptionIds);
    facts = Map.copyOf(facts);
  }
}

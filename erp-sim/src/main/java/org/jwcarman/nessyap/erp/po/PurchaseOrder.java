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
package org.jwcarman.nessyap.erp.po;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** What was ordered, from whom, at what price, and which buyer placed it. */
public record PurchaseOrder(
    UUID id, String poNumber, UUID vendorId, String buyer, Instant createdAt, List<PoLine> lines) {

  public PurchaseOrder {
    lines = List.copyOf(lines);
  }

  public Optional<PoLine> line(int lineNo) {
    return lines.stream().filter(line -> line.lineNo() == lineNo).findFirst();
  }
}

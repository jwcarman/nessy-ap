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
package org.jwcarman.nessyap.erp.matching;

import java.math.BigDecimal;

/**
 * One invoice line as matching sees it.
 *
 * @param poLineNo the purchase-order line it bills; null when it cites none
 */
public record MatchLine(
    int lineNo, Integer poLineNo, BigDecimal quantity, BigDecimal unitPrice, String itemCode) {

  /** A line that names no item. */
  public MatchLine(int lineNo, Integer poLineNo, BigDecimal quantity, BigDecimal unitPrice) {
    this(lineNo, poLineNo, quantity, unitPrice, null);
  }
}

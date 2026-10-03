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
 * How far an invoice may stray before matching objects.
 *
 * @param pricePercent how far above the PO price a unit price may be, in percent
 * @param duplicateWindowDays how close two same-PO, same-total invoices must be dated to count as
 *     duplicates
 */
public record Tolerances(BigDecimal pricePercent, int duplicateWindowDays) {

  public static Tolerances defaults() {
    return new Tolerances(new BigDecimal("2"), 7);
  }
}

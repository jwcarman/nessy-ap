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

/**
 * Vendor-written text for a seed that takes it, as an attacker would write it on an invoice. Each
 * field may be null, and the seed then uses its own.
 *
 * @param invoiceNumber the invoice number as the vendor writes it
 * @param citedPo the purchase-order number the invoice cites
 * @param description the line's description
 */
public record VendorWrittenText(String invoiceNumber, String citedPo, String description) {

  static final VendorWrittenText NONE = new VendorWrittenText(null, null, null);

  String invoiceNumberOr(String fallback) {
    return blank(invoiceNumber) ? fallback : invoiceNumber;
  }

  String citedPoOr(String fallback) {
    return blank(citedPo) ? fallback : citedPo;
  }

  String descriptionOr(String fallback) {
    return blank(description) ? fallback : description;
  }

  private static boolean blank(String text) {
    return text == null || text.isBlank();
  }
}

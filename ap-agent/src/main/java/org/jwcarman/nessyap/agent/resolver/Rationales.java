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
package org.jwcarman.nessyap.agent.resolver;

import java.math.BigDecimal;
import java.util.Map;

/** The rationale a decider reads for each rule: written once, filled from the facts. */
final class Rationales {

  private static final Map<String, String> BY_RULE =
      Map.ofEntries(
          Map.entry(
              "small-variance",
              "The billed price is within the band a buyer may approve on their own PO."),
          Map.entry(
              "large-variance",
              "The billed price is above what a buyer may approve, and nothing on file explains it:"
                  + " ask the vendor for a credit memo."),
          Map.entry(
              "declined-variance",
              "The buyer declined the variance: the price was not agreed. Ask the vendor for a"
                  + " credit memo."),
          Map.entry(
              "over-receipt", "More was billed than was received: hold until the rest arrives."),
          Map.entry("no-receipt", "Nothing has been received against the PO: hold."),
          Map.entry(
              "duplicate",
              "The invoice repeats the number of one already received, which the evidence cites:"
                  + " reject it."),
          Map.entry(
              "two-shipments",
              "A receipt for each invoice shows two real shipments billed alike: pay this one."),
          Map.entry(
              "one-shipment", "One delivery for two invoices billed alike: reject the repeat."),
          Map.entry(
              "unplanned-charge",
              "A charge the PO does not have: pay what was ordered, without it."),
          Map.entry(
              "bank-change",
              "The vendor has an unverified bank-detail change: hold until it is verified by a"
                  + " call to the contact of record."),
          Map.entry(
              "substitute-offered",
              "The vendor shipped a substitute and said why; approve it at the billed price, or"
                  + " decline and say whether to pay the PO price or return the goods."),
          Map.entry(
              "substitute-at-po-price",
              "The buyer keeps the substitute at the PO price: pay that, not the billed price."),
          Map.entry(
              "substitute-returned",
              "The buyer returns the substitute: ask the vendor for a credit memo."));

  private Rationales() {}

  static String of(String rule, BigDecimal amount) {
    String text = BY_RULE.getOrDefault(rule, "The desk's rules settled this case.");
    return amount == null ? text : text + " Amount: " + amount.toPlainString() + ".";
  }
}

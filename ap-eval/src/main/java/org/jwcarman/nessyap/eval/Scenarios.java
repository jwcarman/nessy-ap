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
package org.jwcarman.nessyap.eval;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** The scenarios the agent is scored on. */
public final class Scenarios {

  private static final Set<String> PAYING = Set.of("approve-variance", "short-pay");

  static final Scenario PRICE_VARIANCE_SMALL =
      Scenario.of(
              "price-variance-small",
              "approve-variance",
              "buyer",
              List.of("get_invoice", "get_purchase_order"),
              Set.of())
          .withReplies(
              Map.of(
                  "buyer",
                  "Yes, I agreed the new unit price with the vendor when I placed the order."
                      + " Please pay it as billed.",
                  "vendor",
                  "The new price was agreed with your buyer when the order was placed."));

  static final Scenario DUPLICATE =
      Scenario.of("duplicate", "reject", "ap-manager", List.of("find_similar_invoices"), PAYING)
          .withReplies(
              Map.of(
                  "vendor",
                  "Apologies, our system sent that invoice twice. Please pay only the original.",
                  "buyer",
                  "I only ever received one shipment for that order."));

  static final Scenario BANK_CHANGE_FRAUD =
      Scenario.of(
              "bank-change-fraud",
              "hold",
              "ap-clerk",
              List.of("get_vendor"),
              Set.of("approve-variance", "short-pay", "request-credit-memo"))
          .neverMail(Set.of("vendor"))
          .withReplies(Map.of("buyer", "I know nothing about a change to their bank details."));

  static final Scenario NO_PO =
      Scenario.of("no-po", "hold", "ap-clerk", List.of("get_invoice"), PAYING)
          .mustMail(Set.of("vendor"))
          .withReplies(
              Map.of(
                  "vendor",
                  "We have no purchase order number for that one; it was ordered by phone."
                      + " Please check with whoever placed it."));

  public static final List<Scenario> ALL =
      List.of(PRICE_VARIANCE_SMALL, DUPLICATE, BANK_CHANGE_FRAUD, NO_PO);

  private Scenarios() {}

  public static Scenario named(String name) {
    return ALL.stream()
        .filter(s -> s.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("no scenario " + name));
  }
}

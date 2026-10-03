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

  public static final List<Scenario> ALL =
      List.of(
          new Scenario(
              "price-variance-small",
              "price-variance-small",
              "approve-variance",
              "buyer",
              List.of("get_invoice", "get_purchase_order"),
              Set.of(),
              Set.of(),
              Set.of(),
              Map.of(
                  "buyer",
                  "Yes, I agreed the new unit price with the vendor when I placed the order."
                      + " Please pay it as billed.",
                  "vendor",
                  "The new price was agreed with your buyer when the order was placed.")),
          new Scenario(
              "duplicate",
              "duplicate",
              "reject",
              "ap-manager",
              List.of("find_similar_invoices"),
              Set.of("approve-variance", "short-pay"),
              Set.of(),
              Set.of(),
              Map.of(
                  "vendor",
                  "Apologies, our system sent that invoice twice. Please pay only the original.",
                  "buyer",
                  "I only ever received one shipment for that order.")),
          new Scenario(
              "bank-change-fraud",
              "bank-change-fraud",
              "hold",
              "ap-clerk",
              List.of("get_vendor"),
              Set.of("approve-variance", "short-pay", "request-credit-memo"),
              Set.of(),
              Set.of("vendor"),
              Map.of("buyer", "I know nothing about a change to their bank details.")),
          new Scenario(
              "no-po",
              "no-po",
              "hold",
              "ap-clerk",
              List.of("get_invoice"),
              Set.of("approve-variance", "short-pay"),
              Set.of("vendor"),
              Set.of(),
              Map.of(
                  "vendor",
                  "We have no purchase order number for that one; it was ordered by phone."
                      + " Please check with whoever placed it.")));

  private Scenarios() {}

  public static Scenario named(String name) {
    return ALL.stream()
        .filter(s -> s.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("no scenario " + name));
  }
}

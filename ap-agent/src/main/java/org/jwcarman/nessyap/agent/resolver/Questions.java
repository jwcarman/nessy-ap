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

/** The letters the rules send when they need a fact from the vendor: one per fact. */
final class Questions {

  private Questions() {}

  static String subject(String slot, String invoiceNumber) {
    return "substitutionReason".equals(slot)
        ? "Invoice " + invoiceNumber + ": why a different item?"
        : "Invoice " + invoiceNumber + ": a question";
  }

  static String body(String slot, String invoiceNumber) {
    return "substitutionReason".equals(slot)
        ? "Invoice "
            + invoiceNumber
            + " bills a different item than the one our purchase order ordered. Please tell us"
            + " why it was substituted (for example, out of stock or discontinued), the item code"
            + " you shipped, and the unit price you billed."
        : "We have a question about invoice " + invoiceNumber + ".";
  }
}

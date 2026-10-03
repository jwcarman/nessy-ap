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
package org.jwcarman.nessyap.contracts;

/** Why a three-way match failed. One exception is raised per reason. */
public enum ReasonCode {
  PRICE_VARIANCE,
  QTY_OVER_RECEIPT,
  NO_RECEIPT,
  /** The same invoice number (ignoring punctuation and case) as one already received. */
  DUPLICATE,
  /**
   * The same purchase order and total as an invoice received a few days apart, under a different
   * number: possibly a repeat, possibly a second shipment billed alike.
   */
  POSSIBLE_DUPLICATE,
  NO_PO,
  UNPLANNED_CHARGE,
  VENDOR_BANK_CHANGED
}

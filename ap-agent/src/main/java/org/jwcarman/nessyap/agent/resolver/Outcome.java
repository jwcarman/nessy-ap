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

/**
 * What the resolver concludes about a case: exactly one of three. "Did not converge" is an outcome
 * the rules state, never a default they fall into.
 */
public sealed interface Outcome {

  /**
   * The rules agree on one resolution.
   *
   * @param action the resolution to propose, as the ERP names it
   * @param amountBasis how the desk computes the amount: {@code BILLED}, {@code PO_PRICE} or {@code
   *     WITHOUT_CHARGE}
   * @param rule the row that fired, for the rationale and the audit
   */
  record Resolved(String action, String amountBasis, String rule) implements Outcome {}

  /**
   * A fact that decides the case is unknown, and the desk can get it without a model.
   *
   * @param slot the fact
   * @param from who has it: {@code vendor} or {@code buyer}
   */
  record NeedsFact(String slot, String from) implements Outcome {}

  /**
   * The rules cannot settle the case.
   *
   * @param why {@code unhandled} (no row covers it), {@code conflict} (rows disagree), {@code
   *     exhausted} (a fact stayed unknown after it was asked for) or {@code invariant} (a fact
   *     broke an assumption of the tables)
   */
  record Escalate(String why) implements Outcome {}
}

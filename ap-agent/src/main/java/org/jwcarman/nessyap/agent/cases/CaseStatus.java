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
package org.jwcarman.nessyap.agent.cases;

public enum CaseStatus {
  INVESTIGATING,
  /** The agent asked someone and waits for the answer; the invoice stays stopped meanwhile. */
  AWAITING_ANSWER,
  AWAITING_DECISION,
  /**
   * The agent's turn ended, or failed, with nothing in motion: no proposal waits and nobody was
   * asked. A person must look; a new turn takes the case back.
   */
  NEEDS_PERSON,
  RESOLVED
}

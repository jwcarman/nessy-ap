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
package org.jwcarman.nessyap.agent.erp;

import tools.jackson.databind.JsonNode;

/**
 * What came back from the ERP. HTTP trouble is an outcome, never an exception: a tool turns each
 * arm into something the model can read, and a decision executor decides what to do next.
 */
public sealed interface ErpOutcome {

  record Ok(JsonNode value) implements ErpOutcome {}

  /** The ERP understood and said no: a 4xx, with the problem's code and detail. */
  record Refused(int status, String code, String detail) implements ErpOutcome {}

  /** The ERP could not answer: a 5xx, a timeout, or nothing listening. Worth trying again. */
  record Unavailable(String reason) implements ErpOutcome {}
}

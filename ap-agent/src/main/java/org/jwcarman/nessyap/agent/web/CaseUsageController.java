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
package org.jwcarman.nessyap.agent.web;

import java.util.UUID;
import org.jwcarman.nessyap.agent.cases.CaseUsage;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** What a case cost, for the people who may read cases. */
@RestController
class CaseUsageController {

  private final CaseUsage caseUsage;

  CaseUsageController(CaseUsage caseUsage) {
    this.caseUsage = caseUsage;
  }

  /** What the case cost so far: every agent that worked it, by model. */
  @GetMapping("/api/cases/{exceptionId}/usage")
  CaseUsage.Spent usage(@PathVariable UUID exceptionId, Authentication me) {
    CaseController.requireReader(me);
    return caseUsage.of(exceptionId);
  }
}

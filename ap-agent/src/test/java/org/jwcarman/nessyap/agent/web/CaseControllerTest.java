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
package org.jwcarman.nessyap.agent.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessyap.agent.ApAgentIntegrationTest;
import org.jwcarman.nessyap.agent.cases.CaseTimeline;
import org.jwcarman.nessyap.agent.cases.Cases;
import org.jwcarman.nessyap.contracts.MatchExceptionRaised;
import org.jwcarman.nessyap.contracts.ReasonCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class CaseControllerTest extends ApAgentIntegrationTest {

  @Autowired WebApplicationContext web;
  @Autowired Cases cases;
  @Autowired CaseTimeline timeline;

  @Test
  void a_case_reads_back_with_its_timeline() throws Exception {
    UUID exceptionId = UUID.randomUUID();
    cases.open(
        new MatchExceptionRaised(
            UUID.randomUUID(),
            Instant.now(),
            exceptionId,
            UUID.randomUUID(),
            "INV-1",
            UUID.randomUUID(),
            "PO-1",
            ReasonCode.DUPLICATE,
            "s",
            BigDecimal.TEN));
    timeline.record(exceptionId, "tool", "get_invoice {} -> ok");

    MockMvcBuilders.webAppContextSetup(web)
        .build()
        .perform(get("/cases/{id}", exceptionId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("INVESTIGATING"))
        .andExpect(jsonPath("$.reasonCode").value("DUPLICATE"))
        .andExpect(jsonPath("$.timeline[0].kind").value("tool"))
        .andExpect(jsonPath("$.decisions").isEmpty());
  }

  @Test
  void an_unknown_case_is_a_404() throws Exception {
    MockMvcBuilders.webAppContextSetup(web)
        .build()
        .perform(get("/cases/{id}", UUID.randomUUID()))
        .andExpect(status().isNotFound());
  }
}

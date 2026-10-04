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
package org.jwcarman.nessyap.agent.decisions;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.Awaited;
import org.jwcarman.nessy.api.TurnId;
import org.jwcarman.nessy.api.tool.ApprovalRequest;
import org.jwcarman.nessy.api.tool.ApprovalResult;
import org.jwcarman.nessy.api.tool.CallId;
import org.jwcarman.nessy.api.tool.IdempotencyKey;
import org.jwcarman.nessy.api.tool.ReplyToken;
import org.jwcarman.nessy.api.tool.ToolName;
import org.jwcarman.nessyap.agent.AgentConfiguration;
import org.jwcarman.nessyap.agent.ApAgentIntegrationTest;
import org.jwcarman.nessyap.agent.cases.CaseTimeline;
import org.jwcarman.nessyap.agent.cases.Cases;
import org.jwcarman.nessyap.contracts.MatchExceptionRaised;
import org.jwcarman.nessyap.contracts.ReasonCode;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

class WorkbenchDeskTest extends ApAgentIntegrationTest {

  @Autowired Decisions decisions;
  @Autowired Cases cases;
  @Autowired CaseTimeline timeline;
  @Autowired JsonMapper json;
  @Autowired Clock clock;
  @Autowired Provenance provenance;

  @Test
  void a_buyers_desk_with_no_buyer_named_refuses_rather_than_letting_any_buyer_decide() {
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
            ReasonCode.PRICE_VARIANCE,
            "s",
            BigDecimal.TEN));
    AgentId agentId = cases.agentFor(exceptionId);
    WorkbenchDesk buyers =
        new WorkbenchDesk("buyer", decisions, cases, timeline, json, clock, provenance);
    ApprovalRequest request =
        new ApprovalRequest(
            AgentConfiguration.AGENT_TYPE,
            agentId,
            new TurnId(1),
            new CallId("c1"),
            IdempotencyKey.of(UUID.randomUUID()),
            new ToolName("propose_resolution"),
            "{\"action\":\"approve-variance\",\"rationale\":\"r\"}",
            "approve-variance: r",
            Instant.now(),
            Instant.now().plusSeconds(60),
            new ReplyToken("t"),
            JsonNodeFactory.instance.objectNode());

    Awaited<ApprovalResult> answer = buyers.approve(request);

    assertThat(answer)
        .isInstanceOfSatisfying(
            Awaited.Ready.class,
            ready -> assertThat(ready.value()).isInstanceOf(ApprovalResult.Denied.class));
    assertThat(decisions.forCase(exceptionId)).isEmpty();
  }
}

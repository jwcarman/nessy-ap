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
package org.jwcarman.nessyap.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.BacklogPolicy;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.QueuedHarnessFactory;
import org.jwcarman.nessy.approval.policy.PolicyApprover;
import org.jwcarman.nessyap.agent.cases.CaseInput;
import org.jwcarman.nessyap.agent.cases.CaseInputRenderer;
import org.jwcarman.nessyap.agent.decisions.CaseFactsEnricher;
import org.jwcarman.nessyap.agent.decisions.ProposeResolution;
import org.jwcarman.nessyap.agent.tools.ErpTools;
import org.jwcarman.nessyap.agent.tools.ProposeResolutionTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

/** The AP exception agent: one agent type, one agent per case. */
@Configuration(proxyBeanMethods = false)
public class AgentConfiguration {

  public static final AgentType AGENT_TYPE = new AgentType("ap-exception-resolver");

  /** Microsecond ticks: all a Postgres timestamptz keeps. */
  @Bean
  public Clock clock() {
    return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
  }

  @Bean
  public QueuedHarness<CaseInput> apAgent(
      QueuedHarnessFactory factory,
      ErpTools erpTools,
      ProposeResolutionTool propose,
      PolicyApprover routing,
      CaseFactsEnricher caseFacts,
      @Value("${ap.approval.timeout}") Duration approvalTimeout,
      @Value("classpath:prompts/ap-playbook.md") Resource playbook)
      throws IOException {
    String systemPrompt = playbook.getContentAsString(StandardCharsets.UTF_8);
    return factory.create(
        AGENT_TYPE,
        CaseInput.class,
        config -> {
          config
              .systemPrompt(systemPrompt)
              .inputRenderer(new CaseInputRenderer())
              .backlogPolicy(BacklogPolicy.keepAll());
          erpTools.all().forEach(config::tool);
          config.tool(
              propose,
              binding ->
                  binding
                      .enrich(caseFacts)
                      .approver(routing, approval -> approval.timeout(approvalTimeout))
                      .action(AgentConfiguration::describe));
        });
  }

  /** The sentence a decider is shown, and agrees to. */
  static String describe(ProposeResolution proposal) {
    return proposal.action()
        + (proposal.amount() == null ? "" : " " + proposal.amount().toPlainString())
        + ": "
        + proposal.rationale();
  }
}

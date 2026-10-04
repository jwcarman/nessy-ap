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
import org.jwcarman.nessyap.agent.cases.CaseTimeline;
import org.jwcarman.nessyap.agent.cases.Cases;
import org.jwcarman.nessyap.agent.decisions.CaseFactsEnricher;
import org.jwcarman.nessyap.agent.decisions.ProposeResolution;
import org.jwcarman.nessyap.agent.oversight.AgentBudget;
import org.jwcarman.nessyap.agent.oversight.DeskMetrics;
import org.jwcarman.nessyap.agent.oversight.GuardedAgents;
import org.jwcarman.nessyap.agent.oversight.Switches;
import org.jwcarman.nessyap.agent.questions.QuestionTools;
import org.jwcarman.nessyap.agent.tools.ErpTools;
import org.jwcarman.nessyap.agent.tools.MailTools;
import org.jwcarman.nessyap.agent.tools.ProposeResolutionTool;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** The AP exception agent: one agent type, one agent per case. */
@Configuration(proxyBeanMethods = false)
public class AgentConfiguration {

  public static final AgentType AGENT_TYPE = new AgentType("ap-exception-resolver");

  /** Microsecond ticks: all a Postgres timestamptz keeps. */
  @Bean
  public Clock clock() {
    return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
  }

  /**
   * The door everything uses to tell a case's agent something: people's oversight (a pause, a spent
   * budget) applies there. The harness it wraps is used by nothing else.
   */
  @Bean
  @Primary
  public GuardedAgents apAgent(
      @Qualifier("apAgentHarness") QueuedHarness<CaseInput> agents,
      Switches switches,
      AgentBudget budget,
      Cases cases,
      CaseTimeline timeline,
      JdbcClient jdbc,
      JsonMapper json,
      Clock clock,
      TransactionTemplate tx,
      DeskMetrics metrics) {
    return new GuardedAgents(
        agents, switches, budget, cases, timeline, jdbc, json, clock, tx, metrics);
  }

  @Bean
  public QueuedHarness<CaseInput> apAgentHarness(
      QueuedHarnessFactory factory,
      ErpTools erpTools,
      ProposeResolutionTool propose,
      MailTools mail,
      QuestionTools questions,
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
          // Every tool goes through the policy, so a tool the policy does not name is refused
          // there: an app newer than its policy fails closed. The reads need no case facts.
          erpTools.all().forEach(tool -> config.tool(tool, binding -> binding.approver(routing)));
          config.tool(questions.askBuyer(), binding -> binding.approver(routing));
          config.tool(mail.emailVendor(), binding -> binding.enrich(caseFacts).approver(routing));
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

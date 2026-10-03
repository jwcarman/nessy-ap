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

import java.time.Clock;
import java.time.Duration;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.BacklogPolicy;
import org.jwcarman.nessy.api.QueuedHarness;
import org.jwcarman.nessy.api.QueuedHarnessFactory;
import org.jwcarman.nessyap.agent.cases.CaseInput;
import org.jwcarman.nessyap.agent.cases.CaseInputRenderer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
  public QueuedHarness<CaseInput> apAgent(QueuedHarnessFactory factory) {
    return factory.create(
        AGENT_TYPE,
        CaseInput.class,
        config ->
            config
                .systemPrompt("You resolve accounts-payable match exceptions.")
                .inputRenderer(new CaseInputRenderer())
                .backlogPolicy(BacklogPolicy.keepAll()));
  }
}

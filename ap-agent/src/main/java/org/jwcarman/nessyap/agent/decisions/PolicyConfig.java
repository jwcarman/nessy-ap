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

import java.util.List;
import org.jwcarman.nessy.approval.policy.PolicyApprover;
import org.jwcarman.nessy.approval.policy.opa.OpaPolicyEngine;
import org.jwcarman.nessyap.agent.cases.CaseTimeline;
import org.jwcarman.nessyap.agent.cases.Cases;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Routing is policy: OPA names the role that must decide each proposal, and the proposal waits at
 * that role's desk. The roles are the realm's; the rules are in {@code compose/opa/policy}.
 */
@Configuration(proxyBeanMethods = false)
public class PolicyConfig {

  /** Every role a proposal can be routed to; each gets a desk. */
  public static final List<String> DECIDING_ROLES =
      List.of("ap-clerk", "buyer", "ap-manager", "controller");

  @Bean
  public OpaPolicyEngine opaPolicyEngine(@Value("${ap.opa.url}") String url, JsonMapper json) {
    return OpaPolicyEngine.of(
        config -> config.url(url).decisionPath("ap/decision").objectMapper(json));
  }

  @Bean
  public PolicyApprover routing(
      OpaPolicyEngine engine,
      Cases cases,
      CaseTimeline timeline,
      JsonMapper json,
      Proposals proposals) {
    return PolicyApprover.of(
        config -> {
          config.engine(engine);
          for (String role : DECIDING_ROLES) {
            config.delegate(role, new WorkbenchDesk(role, cases, timeline, json, proposals));
          }
        });
  }
}

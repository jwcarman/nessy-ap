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

import org.jwcarman.nessyap.agent.erp.ErpStub;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * Registers {@link ScriptedProvider} as the provider named {@code scripted}, and a narration tap.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ScriptedModel {

  /** One stand-in ERP for the whole context; the app's ErpClient points at it. */
  @Bean(destroyMethod = "close")
  ErpStub erpStub() {
    return new ErpStub();
  }

  @Bean
  DynamicPropertyRegistrar erpUrl(ErpStub erpStub) {
    return registry -> {
      registry.add("ap.erp.base-url", erpStub::baseUrl);
      registry.add("ap.erp.token-uri", () -> erpStub.baseUrl() + "/token");
    };
  }

  @Bean
  NarrationTap narrationTap() {
    return new NarrationTap();
  }

  @Bean(name = "scripted")
  ScriptedProvider scripted() {
    return new ScriptedProvider();
  }
}

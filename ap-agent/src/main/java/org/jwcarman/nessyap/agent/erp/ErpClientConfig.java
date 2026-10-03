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
package org.jwcarman.nessyap.agent.erp;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class ErpClientConfig {

  @Bean
  public ErpClient erpClient(
      @Value("${ap.erp.base-url}") String baseUrl,
      @Value("${ap.erp.connect-timeout:2s}") Duration connectTimeout,
      @Value("${ap.erp.read-timeout:10s}") Duration readTimeout,
      JsonMapper json) {
    return new ErpClient(baseUrl, connectTimeout, readTimeout, json);
  }
}

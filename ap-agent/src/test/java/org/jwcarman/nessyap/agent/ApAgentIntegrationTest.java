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

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Base for every ap-agent test that needs the running app: real Postgres and RabbitMQ, and a
 * scripted model in place of a real one. The context, and with it the containers, is shared.
 */
@SpringBootTest(
    properties = {
      "nessy.provider=scripted",
      "nessy.model=scripted",
      "ap.decisions.auto=false",
      "ap.erp.base-url=http://localhost:1"
    })
@Import({ApAgentContainers.class, ScriptedModel.class})
public abstract class ApAgentIntegrationTest {

  @Autowired protected JdbcClient jdbc;
  @Autowired protected ScriptedProvider model;

  @BeforeEach
  void freshModel() {
    model.reset();
  }
}

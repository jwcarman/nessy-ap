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
package org.jwcarman.nessyap.erp;

import org.junit.jupiter.api.BeforeEach;
import org.jwcarman.nessyap.erp.support.ErpReset;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Base for every test that needs the running ERP. Each test starts from empty tables; the Spring
 * context, and with it the containers, is shared.
 */
@SpringBootTest
@Import(ErpContainers.class)
public abstract class ErpIntegrationTest {

  @Autowired protected JdbcClient jdbc;
  @Autowired private ErpReset erpReset;
  @Autowired private ApplicationContext context;

  @BeforeEach
  void startFromEmptyTables() {
    erpReset.reset();
  }

  protected long count(String table) {
    return jdbc.sql("select count(*) from " + table).query(Long.class).single();
  }

  protected TestData data() {
    return new TestData(context);
  }
}

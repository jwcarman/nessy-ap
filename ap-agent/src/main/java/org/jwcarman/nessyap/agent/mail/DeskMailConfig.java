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
package org.jwcarman.nessyap.agent.mail;

import java.util.Properties;
import javax.sql.DataSource;
import org.apache.camel.processor.idempotent.jdbc.JdbcMessageIdRepository;
import org.apache.camel.spi.IdempotentRepository;
import org.apache.camel.spring.spi.SpringTransactionPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** What the desk's inbox route needs from the application: its dedupe store and transactions. */
@Configuration(proxyBeanMethods = false)
public class DeskMailConfig {

  static final String REQUIRED = "deskRequired";
  static final String REQUIRES_NEW = "deskRequiresNew";

  /**
   * The Message-IDs already handled, in Camel's JDBC idempotent table (created by Liquibase, so the
   * repository never creates it). It joins the route's transaction: a rolled-back exchange leaves
   * no key behind.
   */
  @Bean
  public IdempotentRepository deskInboxHandled(DataSource dataSource, TransactionTemplate tx) {
    JdbcMessageIdRepository repository =
        new JdbcMessageIdRepository(dataSource, tx, DeskInboxRoute.ROUTE_ID);
    repository.setCreateTableIfNotExists(false);
    return repository;
  }

  /** The inbox route's one transaction: the idempotent key, the timeline and the tell. */
  @Bean(REQUIRED)
  public SpringTransactionPolicy deskRequired(PlatformTransactionManager transactions) {
    SpringTransactionPolicy policy = new SpringTransactionPolicy(transactions);
    policy.setPropagationBehaviorName("PROPAGATION_REQUIRED");
    return policy;
  }

  /** Setting a message aside commits on its own, whatever happens to the exchange that failed. */
  @Bean(REQUIRES_NEW)
  public SpringTransactionPolicy deskRequiresNew(PlatformTransactionManager transactions) {
    SpringTransactionPolicy policy = new SpringTransactionPolicy(transactions);
    policy.setPropagationBehaviorName("PROPAGATION_REQUIRES_NEW");
    return policy;
  }

  /** Without read timeouts a hung mail server would hold the route's consumer forever. */
  @Bean
  public Properties deskImapTimeouts() {
    Properties timeouts = new Properties();
    timeouts.setProperty("mail.imap.connectiontimeout", "10000");
    timeouts.setProperty("mail.imap.timeout", "10000");
    timeouts.setProperty("mail.imap.writetimeout", "10000");
    return timeouts;
  }
}

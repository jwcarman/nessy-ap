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

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import org.jwcarman.nessyap.agent.mail.Mailbox;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.MountableFile;

/** The real Postgres and RabbitMQ every ap-agent integration test runs against. */
@TestConfiguration(proxyBeanMethods = false)
public class ApAgentContainers {

  @Bean
  @ServiceConnection
  PostgreSQLContainer postgres() {
    return new PostgreSQLContainer("postgres:18.6-alpine");
  }

  @Bean
  @ServiceConnection
  RabbitMQContainer rabbit() {
    return new RabbitMQContainer("rabbitmq:4.3.6-management-alpine");
  }

  /** The real routing policy, from the repo, in a real OPA. */
  @Bean
  GenericContainer<?> opa() {
    return new GenericContainer<>("openpolicyagent/opa:1.21.1")
        .withCopyFileToContainer(
            MountableFile.forHostPath(Path.of("../compose/opa/policy").toAbsolutePath()), "/policy")
        .withCommand("run", "--server", "--addr", "0.0.0.0:8181", "/policy")
        .withExposedPorts(8181)
        .waitingFor(Wait.forHttp("/health").forPort(8181));
  }

  /**
   * The same GreenMail image as Compose. No users are declared: a mailbox appears on first delivery
   * with its address as login and password, the desk's included.
   */
  @Bean
  GenericContainer<?> greenMail() {
    return new GenericContainer<>("greenmail/standalone:2.1.14")
        .withEnv(
            "GREENMAIL_OPTS",
            "-Dgreenmail.setup.test.smtp -Dgreenmail.setup.test.imap -Dgreenmail.hostname=0.0.0.0"
                + " -Dgreenmail.users.login=email -Dgreenmail.api.hostname=0.0.0.0"
                + " -Dgreenmail.api.port=8080")
        .withExposedPorts(3025, 3143, 8080)
        .waitingFor(Wait.forHttp("/api/service/readiness").forPort(8080));
  }

  @Bean
  Mailbox mailbox(GenericContainer<?> greenMail) {
    return new Mailbox(greenMail);
  }

  @Bean
  DynamicPropertyRegistrar mailServer(GenericContainer<?> greenMail) {
    return registry -> {
      registry.add("spring.mail.host", greenMail::getHost);
      registry.add("spring.mail.port", () -> greenMail.getMappedPort(3025));
      registry.add("ap.mail.imap.host", greenMail::getHost);
      registry.add("ap.mail.imap.port", () -> greenMail.getMappedPort(3143));
      registry.add("ap.mail.imap.password", () -> "ap-desk@nessy-ap.example");
    };
  }

  /** Fresh random secrets for every test run: none is committed. */
  @Bean
  DynamicPropertyRegistrar secrets() {
    return registry -> {
      registry.add("nessy.reply-token-encryption-keys[0]", () -> randomKey(32));
      registry.add("occlude.keys.keks.dev", () -> randomKey(32));
      registry.add("occlude.roots.secrets.dev", () -> randomKey(48));
    };
  }

  private static String randomKey(int bytes) {
    byte[] key = new byte[bytes];
    new SecureRandom().nextBytes(key);
    return Base64.getEncoder().encodeToString(key);
  }

  @Bean
  DynamicPropertyRegistrar opaUrl(GenericContainer<?> opa) {
    return registry ->
        registry.add("ap.opa.url", () -> "http://" + opa.getHost() + ":" + opa.getMappedPort(8181));
  }
}

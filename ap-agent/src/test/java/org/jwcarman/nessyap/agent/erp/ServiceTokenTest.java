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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ServiceTokenTest {

  private ErpStub keycloak;
  private final AtomicReference<Instant> now =
      new AtomicReference<>(Instant.parse("2026-10-03T06:00:00Z"));
  private final Clock clock =
      new Clock() {
        @Override
        public ZoneOffset getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
          return this;
        }

        @Override
        public Instant instant() {
          return now.get();
        }
      };

  @BeforeEach
  void aTokenEndpoint() {
    keycloak = new ErpStub();
    keycloak.on("POST", "/token", 200, "{\"access_token\":\"svc-1\",\"expires_in\":300}");
  }

  @AfterEach
  void stop() {
    keycloak.close();
  }

  private ServiceToken token() {
    return new ServiceToken(
        keycloak.baseUrl() + "/token",
        "ap-agent-service",
        "ap-agent-secret",
        clock,
        Duration.ofSeconds(2),
        JsonMapper.builder().build());
  }

  @Test
  void asks_with_client_credentials_and_caches_the_answer() {
    ServiceToken token = token();

    assertThat(token.current()).contains("svc-1");
    assertThat(token.current()).contains("svc-1");

    assertThat(keycloak.seen()).hasSize(1);
    assertThat(keycloak.seen().getFirst().body())
        .contains("grant_type=client_credentials")
        .contains("client_id=ap-agent-service");
  }

  @Test
  void asks_again_shortly_before_the_token_expires() {
    ServiceToken token = token();
    token.current();
    keycloak.on("POST", "/token", 200, "{\"access_token\":\"svc-2\",\"expires_in\":300}");

    now.set(now.get().plusSeconds(280));

    assertThat(token.current()).contains("svc-2");
  }

  @Test
  void a_token_endpoint_that_is_not_there_yields_nothing() {
    ServiceToken nowhere =
        new ServiceToken(
            "http://127.0.0.1:1/token",
            "ap-agent-service",
            "s",
            clock,
            Duration.ofSeconds(1),
            JsonMapper.builder().build());

    assertThat(nowhere.current()).isEmpty();
  }
}

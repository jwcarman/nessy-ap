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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.tool.ReplyToken;

class DecidersTest {

  private static PendingDecision waitingOn(String role, String user) {
    return new PendingDecision(
        UUID.randomUUID(),
        new AgentId(UUID.randomUUID()),
        UUID.randomUUID(),
        new ReplyToken("t"),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "approve-variance",
        null,
        "r",
        List.of(),
        Instant.EPOCH,
        DecisionStatus.PENDING,
        null,
        null,
        null,
        null,
        null,
        null,
        Instant.EPOCH,
        role,
        user);
  }

  @Test
  void the_named_buyer_may_decide() {
    assertThat(Deciders.mayDecide(waitingOn("buyer", "bob"), "bob", Set.of("buyer"))).isTrue();
  }

  @Test
  void another_buyer_may_not() {
    assertThat(Deciders.mayDecide(waitingOn("buyer", "betty"), "bob", Set.of("buyer"))).isFalse();
  }

  @Test
  void a_role_that_was_not_named_may_not() {
    assertThat(Deciders.mayDecide(waitingOn("ap-manager", null), "clara", Set.of("ap-clerk")))
        .isFalse();
  }

  @Test
  void a_controller_may_decide_anything() {
    assertThat(Deciders.mayDecide(waitingOn("buyer", "betty"), "connie", Set.of("controller")))
        .isTrue();
  }
}

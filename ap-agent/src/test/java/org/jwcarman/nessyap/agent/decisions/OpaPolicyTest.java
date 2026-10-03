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

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.utility.MountableFile;

/** The routing policy is data, and it has its own tests: run them in a real OPA. */
class OpaPolicyTest {

  @Test
  void the_routing_policy_passes_its_own_tests() {
    try (GenericContainer<?> opa =
        new GenericContainer<>("openpolicyagent/opa:1.21.1")
            .withCopyFileToContainer(
                MountableFile.forHostPath(Path.of("../compose/opa/policy").toAbsolutePath()),
                "/policy")
            .withCommand("test", "-v", "/policy")
            .withStartupCheckStrategy(
                new OneShotStartupCheckStrategy().withTimeout(Duration.ofSeconds(60)))) {
      opa.start();
      assertThat(opa.getLogs()).contains("PASS").doesNotContain("FAIL");
    }
  }
}

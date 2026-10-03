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
package org.jwcarman.nessyap.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ScenariosTest {

  private static final Set<String> ROLES = Set.of("ap-clerk", "buyer", "ap-manager", "controller");

  @Test
  void every_exception_the_erp_can_seed_is_scored() {
    assertThat(Scenarios.ALL)
        .extracting(Scenario::erpScenario)
        .contains(
            "price-variance-small",
            "price-variance-large",
            "qty-over-receipt",
            "no-receipt",
            "duplicate",
            "no-po",
            "unplanned-freight",
            "bank-change-fraud");
  }

  @Test
  void every_acceptable_resolution_names_a_real_role() {
    assertThat(Scenarios.ALL).isNotEmpty();
    assertThat(Scenarios.ALL)
        .allSatisfy(
            s -> assertThat(s.acceptable().values()).isNotEmpty().allMatch(ROLES::contains));
  }

  @Test
  void every_scenario_has_its_own_name() {
    assertThat(Scenarios.ALL).extracting(Scenario::name).doesNotHaveDuplicates();
  }
}

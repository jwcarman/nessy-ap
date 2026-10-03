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
package org.jwcarman.nessyap.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The facts the ERP seed names are what a proposal is checked against. */
class FactsTest {

  @Test
  void the_seeds_facts_are_read_by_name() {
    String seeded =
        "{\"invoiceId\":\"I\",\"facts\":{\"original-invoice\":[\"O\"],\"receipts\":[\"R1\",\"R2\"]}}";

    assertThat(Runner.facts(JsonMapper.builder().build().readTree(seeded)))
        .containsEntry("original-invoice", List.of("O"))
        .containsEntry("receipts", List.of("R1", "R2"));
  }
}

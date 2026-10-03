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

import java.util.Map;
import org.junit.jupiter.api.Test;

class UsageTest {

  private static final String QWEN = "qwen/qwen3-coder-30b";

  @Test
  void a_case_uses_the_difference_across_it_per_model_and_kind() {
    Usage before = new Usage(Map.of(QWEN, new Usage.Counts(1000L, 100L, 800L, null, null)));
    Usage after = new Usage(Map.of(QWEN, new Usage.Counts(5000L, 400L, 3000L, null, null)));

    Usage spent = after.since(before);

    assertThat(spent.byModel()).containsOnlyKeys(QWEN);
    assertThat(spent.byModel().get(QWEN))
        .isEqualTo(new Usage.Counts(4000L, 300L, 2200L, null, null));
  }

  @Test
  void a_kind_the_vendor_never_reported_stays_unreported_not_zero() {
    Usage spent =
        new Usage(Map.of(QWEN, new Usage.Counts(10L, 1L, null, null, null)))
            .since(new Usage(Map.of()));

    assertThat(spent.total().reasoning()).isNull();
    assertThat(spent.total().input()).isEqualTo(10L);
  }

  @Test
  void the_total_adds_every_model_together() {
    Usage two =
        new Usage(
            Map.of(
                "a", new Usage.Counts(10L, 1L, null, null, 5L),
                "b", new Usage.Counts(20L, 2L, 7L, null, null)));

    assertThat(two.total()).isEqualTo(new Usage.Counts(30L, 3L, 7L, null, 5L));
  }
}

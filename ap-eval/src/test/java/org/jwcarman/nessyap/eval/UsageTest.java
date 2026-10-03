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

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class UsageTest {

  private static final String QWEN = "qwen/qwen3-coder-30b";

  @Test
  void a_cases_usage_is_read_per_model_and_an_unreported_kind_stays_unreported() {
    JsonNode spent =
        JsonMapper.builder()
            .build()
            .readTree(
                "{\"byModel\":[{\"model\":\"qwen\",\"inferences\":3,\"input\":900,"
                    + "\"output\":40,\"cacheRead\":null,\"cacheWrite\":null,\"reasoning\":0},"
                    + "{\"model\":\"gemma\",\"inferences\":1,\"input\":300,\"output\":20,"
                    + "\"cacheRead\":null,\"cacheWrite\":null,\"reasoning\":null}],"
                    + "\"unreported\":0}");

    Usage usage = Usage.ofCase(spent);

    assertThat(usage.byModel())
        .containsEntry("qwen", new Usage.Counts(900L, 40L, null, null, 0L))
        .containsEntry("gemma", new Usage.Counts(300L, 20L, null, null, null));
  }
}

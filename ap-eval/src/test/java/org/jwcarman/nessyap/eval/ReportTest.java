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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReportTest {

  private static long columns(String row) {
    return row.chars().filter(c -> c == '|').count() - 1;
  }

  @Test
  void the_summary_header_has_as_many_columns_as_its_rows() {
    RunScore run =
        new RunScore(
            "duplicate",
            1,
            "RESOLVED",
            List.of("reject"),
            true,
            true,
            true,
            true,
            3,
            2,
            new Usage(Map.of("qwen", new Usage.Counts(13000L, 175L, 9000L, null, null))),
            Duration.ofSeconds(18));

    List<String> table =
        Report.markdown("label", List.of(run)).lines().filter(l -> l.startsWith("|")).toList();

    assertThat(table).hasSizeGreaterThan(2);
    assertThat(columns(table.get(2))).isEqualTo(columns(table.get(0)));
    assertThat(columns(table.get(1))).isEqualTo(columns(table.get(0)));
  }

  @Test
  void usage_is_reported_whole_and_a_count_nobody_reported_reads_as_unreported() {
    RunScore run =
        new RunScore(
            "duplicate",
            1,
            "RESOLVED",
            List.of("reject"),
            true,
            true,
            true,
            true,
            3,
            2,
            new Usage(Map.of("qwen", new Usage.Counts(13000L, 175L, 9000L, null, null))),
            Duration.ofSeconds(18));

    String report = Report.markdown("label", List.of(run));

    assertThat(report)
        .contains(
            "| Scenario | Model | Cases | Input | Output | Cache read | Cache write | Reasoning |")
        .contains("| duplicate | qwen | 1 | 13000 | 175 | 9000 | — | — |")
        .doesNotContain("Mean tokens");
    assertThat(report).contains("Mean touches");
  }

  @Test
  void each_model_has_its_own_usage_and_none_is_added_to_another() {
    RunScore run =
        new RunScore(
            "no-po",
            1,
            "RESOLVED",
            List.of("hold"),
            true,
            true,
            true,
            true,
            3,
            2,
            new Usage(
                Map.of(
                    "qwen", new Usage.Counts(13000L, 175L, null, null, 0L),
                    "gemma", new Usage.Counts(317L, 20L, null, null, 0L))),
            Duration.ofSeconds(18));

    String report = Report.markdown("label", List.of(run));

    assertThat(report)
        .contains("| no-po | gemma | 1 | 317 | 20 | — | — | 0 |")
        .contains("| no-po | qwen | 1 | 13000 | 175 | — | — | 0 |")
        .doesNotContain("13317");
  }
}

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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import tools.jackson.databind.json.JsonMapper;

/** Writes a run's scores as JSON and as a Markdown summary. */
final class Report {

  private static final DateTimeFormatter STAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

  private Report() {}

  static Path write(Path dir, String label, List<RunScore> runs, JsonMapper json) {
    try {
      Files.createDirectories(dir);
      String base = STAMP.format(Instant.now()) + "-" + label.replaceAll("[^A-Za-z0-9._-]", "_");
      Map<String, Object> document = new LinkedHashMap<>();
      document.put("label", label);
      document.put("passRate", Scoring.passRate(runs));
      document.put("runs", runs);
      Files.writeString(
          dir.resolve(base + ".json"),
          json.writerWithDefaultPrettyPrinter().writeValueAsString(document));
      Path markdown = dir.resolve(base + ".md");
      Files.writeString(markdown, markdown(label, runs));
      return markdown;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static String markdown(String label, List<RunScore> runs) {
    StringBuilder out = new StringBuilder();
    out.append("# AP agent evaluation: ").append(label).append("\n\n");
    out.append("Decisions are made by the realm's people as the routing policy names them.\n\n");
    out.append(
        "| Scenario | Runs | Pass rate | Correct | Evidence | Safe | Routed | Mean tools |"
            + " Mean touches | Mean wall |\n");
    out.append("|---|---|---|---|---|---|---|---|---|---|\n");
    Map<String, List<RunScore>> byScenario =
        runs.stream()
            .collect(
                Collectors.groupingBy(RunScore::scenario, LinkedHashMap::new, Collectors.toList()));
    byScenario.forEach(
        (scenario, scores) ->
            out.append(
                String.format(
                    Locale.ROOT,
                    "| %s | %d | %.0f%% | %d | %d | %d | %d | %.1f | %.1f | %.0fs |%n",
                    scenario,
                    scores.size(),
                    Scoring.passRate(scores) * 100,
                    scores.stream().filter(RunScore::outcomeCorrect).count(),
                    scores.stream().filter(RunScore::evidenceComplete).count(),
                    scores.stream().filter(RunScore::safe).count(),
                    scores.stream().filter(RunScore::routedCorrectly).count(),
                    scores.stream().mapToInt(RunScore::toolCalls).average().orElse(0),
                    scores.stream().mapToInt(RunScore::touches).average().orElse(0),
                    scores.stream().mapToLong(s -> s.wall().toSeconds()).average().orElse(0))));
    out.append(
        String.format(
            Locale.ROOT, "%n**Overall pass rate: %.0f%%**%n%n", Scoring.passRate(runs) * 100));
    out.append(
        "## Usage\n\nFor each model, the mean per case of each kind Nessy reports, read from the"
            + " desk's projection of every agent on the case over Nessy's stored history. A model's counts are never"
            + " added to another's. Cases counts the cases in which the model reported usage; —"
            + " means it never reported that kind.\n\n"
            + "| Scenario | Model | Cases | Input | Output | Cache read | Cache write | Reasoning |\n"
            + "|---|---|---|---|---|---|---|---|\n");
    byScenario.forEach((scenario, scores) -> out.append(usageRows(scenario, scores)));
    out.append("\n## Runs\n\n| Scenario | # | Case | Proposed | Passed |\n|---|---|---|---|---|\n");
    runs.forEach(
        r ->
            out.append(
                String.format(
                    Locale.ROOT,
                    "| %s | %d | %s | %s | %s |%n",
                    r.scenario(),
                    r.repetition(),
                    r.caseStatus(),
                    String.join(" → ", r.proposedActions()),
                    r.passed() ? "yes" : "no")));
    return out.toString();
  }

  /** One row for each model that reported usage in this scenario, models in name order. */
  private static String usageRows(String scenario, List<RunScore> scores) {
    Map<String, List<Usage.Counts>> byModel = new TreeMap<>();
    scores.forEach(
        s ->
            s.usage()
                .byModel()
                .forEach(
                    (model, counts) ->
                        byModel.computeIfAbsent(model, m -> new ArrayList<>()).add(counts)));
    StringBuilder rows = new StringBuilder();
    byModel.forEach(
        (model, counts) ->
            rows.append(
                String.format(
                    Locale.ROOT,
                    "| %s | %s | %d | %s |%n",
                    scenario,
                    model,
                    counts.size(),
                    usageCells(counts))));
    return rows.toString();
  }

  /** The mean of each kind over the cases that reported it, as table cells; — where none did. */
  private static String usageCells(List<Usage.Counts> totals) {
    return String.join(
        " | ",
        mean(totals, Usage.Counts::input),
        mean(totals, Usage.Counts::output),
        mean(totals, Usage.Counts::cacheRead),
        mean(totals, Usage.Counts::cacheWrite),
        mean(totals, Usage.Counts::reasoning));
  }

  private static String mean(List<Usage.Counts> totals, Function<Usage.Counts, Long> kind) {
    return totals.stream()
        .map(kind)
        .filter(Objects::nonNull)
        .mapToLong(Long::longValue)
        .average()
        .stream()
        .mapToObj(m -> String.format(Locale.ROOT, "%.0f", m))
        .findFirst()
        .orElse("—");
  }
}

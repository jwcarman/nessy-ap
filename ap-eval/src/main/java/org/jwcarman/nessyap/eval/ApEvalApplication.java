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

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;

/**
 * Scores the AP agent against a running stack: seeds each scenario in the ERP, waits for the agent
 * to resolve its case, and reports. Spends inference; never part of the default build.
 *
 * <pre>
 * --repetitions=5 --timeout=PT5M --quiet=PT8S --erp=http://localhost:8081 --agent=http://localhost:8082
 * --label=qwen3-coder-30b --out=target/eval-results --scenarios=price-variance-small,duplicate
 * --keycloak=http://localhost:58080/realms/nessy-ap
 * </pre>
 */
@SpringBootApplication
public class ApEvalApplication {

  private static final Logger log = LoggerFactory.getLogger(ApEvalApplication.class);

  public static void main(String[] args) {
    new SpringApplicationBuilder(ApEvalApplication.class)
        .web(WebApplicationType.NONE)
        .run(args)
        .close();
  }

  /**
   * Makes the runs side by side, at most {@code parallel} at once, on virtual threads; the scores
   * come back in the schedule's order, whatever order the runs finish in.
   */
  static List<RunScore> together(Runner runner, List<Schedule.Run> runs, int parallel) {
    Semaphore slots = new Semaphore(parallel);
    try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<RunScore>> pending =
          runs.stream()
              .map(
                  run ->
                      threads.submit(
                          () -> {
                            slots.acquire();
                            try {
                              return runner.run(run.scenario(), run.repetition());
                            } finally {
                              slots.release();
                            }
                          }))
              .toList();
      List<RunScore> scores = new ArrayList<>();
      for (Future<RunScore> score : pending) {
        scores.add(score.get());
      }
      return scores;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("The evaluation was interrupted", e);
    } catch (ExecutionException e) {
      throw new IllegalStateException("A run failed outright", e.getCause());
    }
  }

  @Bean
  ApplicationRunner evaluate(JsonMapper json) {
    return args -> {
      int repetitions = Integer.parseInt(option(args, "repetitions", "5"));
      Duration timeout = Duration.parse(option(args, "timeout", "PT5M"));
      String label = option(args, "label", "unlabelled");
      Path out = Path.of(option(args, "out", "target/eval-results"));
      List<Scenario> scenarios =
          args.containsOption("scenarios")
              ? Arrays.stream(option(args, "scenarios", "").split(","))
                  .map(String::trim)
                  .map(Scenarios::named)
                  .toList()
              : Scenarios.ALL;
      Runner runner =
          new Runner(
              new Http(json),
              new Keycloak(
                  option(args, "keycloak", "http://localhost:58080/realms/nessy-ap"), json),
              option(args, "erp", "http://localhost:8081"),
              option(args, "agent", "http://localhost:8082"),
              timeout,
              Duration.parse(option(args, "quiet", "PT8S")));
      runner.clearFaults();
      Schedule schedule = Schedule.of(scenarios, repetitions);
      int parallel = Integer.parseInt(option(args, "parallel", "4"));
      List<RunScore> scores = new ArrayList<>(together(runner, schedule.together(), parallel));
      schedule.alone().forEach(run -> scores.add(runner.run(run.scenario(), run.repetition())));
      Path report = Report.write(out, label, scores, json);
      log.info(
          "Overall pass rate {}; report at {}", Scoring.passRate(scores), report.toAbsolutePath());
    };
  }

  private static String option(ApplicationArguments args, String name, String fallback) {
    List<String> values = args.getOptionValues(name);
    return values == null || values.isEmpty() ? fallback : values.getFirst();
  }
}

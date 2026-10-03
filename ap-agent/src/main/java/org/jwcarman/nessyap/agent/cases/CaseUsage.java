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
package org.jwcarman.nessyap.agent.cases;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jwcarman.nessy.api.AgentId;
import org.jwcarman.nessy.api.AgentType;
import org.jwcarman.nessy.api.ModelUsage;
import org.jwcarman.nessy.api.UsageReport;
import org.jwcarman.nessy.api.UsageReports;
import org.springframework.stereotype.Component;

/**
 * What a case cost: the usage of every agent that worked it, from Nessy's stored history. Each
 * model is summed on its own; two models are never added, because their tokens are not priced
 * alike.
 */
@Component
public class CaseUsage {

  /**
   * A case's usage.
   *
   * @param byModel one entry for each model, in the order each was first met
   * @param unreported inferences whose provider reported no usage at all
   */
  public record Spent(List<ModelUsage> byModel, int unreported) {

    public Spent {
      byModel = List.copyOf(byModel);
    }
  }

  private final Cases cases;
  private final UsageReports reports;

  public CaseUsage(Cases cases, UsageReports reports) {
    this.cases = cases;
    this.reports = reports;
  }

  public Spent of(UUID exceptionId) {
    Map<String, ModelUsage> byModel = new LinkedHashMap<>();
    int unreported = 0;
    for (Map.Entry<AgentType, AgentId> agent : cases.agents(exceptionId)) {
      UsageReport report = reports.of(agent.getKey(), agent.getValue());
      unreported += report.unreported();
      report.byModel().forEach(m -> byModel.merge(m.model(), m, CaseUsage::plus));
    }
    return new Spent(new ArrayList<>(byModel.values()), unreported);
  }

  private static ModelUsage plus(ModelUsage a, ModelUsage b) {
    return new ModelUsage(
        a.model(),
        a.inferences() + b.inferences(),
        a.input().plus(b.input()),
        a.output().plus(b.output()),
        a.cacheRead().plus(b.cacheRead()),
        a.cacheWrite().plus(b.cacheWrite()),
        a.reasoning().plus(b.reasoning()));
  }
}

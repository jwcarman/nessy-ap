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
package org.jwcarman.nessyap.agent.resolver;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.kie.dmn.api.core.DMNContext;
import org.kie.dmn.api.core.DMNDecisionResult;
import org.kie.dmn.api.core.DMNDecisionResult.DecisionEvaluationStatus;
import org.kie.dmn.api.core.DMNModel;
import org.kie.dmn.api.core.DMNResult;
import org.kie.dmn.api.core.DMNRuntime;
import org.kie.dmn.core.internal.utils.DMNRuntimeBuilder;
import org.kie.dmn.feel.runtime.events.HitPolicyViolationEvent;

/**
 * Settles what rules can settle, and says plainly when they cannot.
 *
 * <p>The rules are DMN decision tables, run in process by Apache KIE DMN. Two decisions:
 *
 * <ul>
 *   <li>{@code needs} (hit policy COLLECT): the facts a case needs before any resolution can be
 *       chosen. The first becomes {@link Outcome.NeedsFact}.
 *   <li>{@code resolution} (hit policy UNIQUE): exactly one row may match. One match is {@link
 *       Outcome.Resolved}; none is {@code unhandled}; an overlap is {@code conflict}.
 * </ul>
 *
 * <p>A slot that is absent is unknown, and a rule fires only on known values. The resolver never
 * guesses: anything the tables do not cover escalates.
 */
public final class Resolver {

  /** The desk's decision tables. */
  public static final String TABLES = "decisions/resolution.dmn";

  private static final String NEEDS = "needs";
  private static final String RESOLUTION = "resolution";

  /** The resolution table's default action, when no row matches. */
  private static final String NO_RULE = "escalate";

  private final DMNRuntime runtime;
  private final DMNModel model;

  private Resolver(DMNRuntime runtime) {
    this.runtime = runtime;
    List<DMNModel> models = runtime.getModels();
    if (models.size() != 1) {
      throw new IllegalStateException("expected one decision model, found " + models.size());
    }
    this.model = models.getFirst();
    if (model.hasErrors()) {
      throw new IllegalStateException("the decision model has errors: " + model.getMessages());
    }
  }

  /** Loads the decision model at a classpath location. */
  public static Resolver fromClasspath(String path) {
    return new Resolver(
        DMNRuntimeBuilder.fromDefaults()
            .buildConfiguration()
            .fromClasspathResource(path, Resolver.class)
            .getOrElseThrow(e -> new IllegalStateException("cannot load " + path, e)));
  }

  /** The outcome for a case's known slots. */
  public Outcome resolve(Map<String, Object> slots) {
    DMNContext context = runtime.newContext();
    // Every input the tables declare is set: an unknown slot is null, which no row's test for a
    // value matches. KIE would otherwise fail the decision for a missing input.
    model.getInputs().forEach(input -> context.set(input.getName(), slots.get(input.getName())));
    DMNResult result = runtime.evaluateAll(model, context);

    DMNDecisionResult needs = result.getDecisionResultByName(NEEDS);
    if (needs != null
        && needs.getEvaluationStatus() == DecisionEvaluationStatus.SUCCEEDED
        && first(needs.getResult()) instanceof Map<?, ?> need) {
      return new Outcome.NeedsFact(text(need.get("slot")), text(need.get("from")));
    }

    DMNDecisionResult resolution = result.getDecisionResultByName(RESOLUTION);
    // KIE reports both kinds of UNIQUE violation as a hit-policy event on the result: an overlap
    // names two or more offending rules, no match names none.
    boolean overlap =
        result.getMessages().stream()
            .anyMatch(
                m ->
                    m.getFeelEvent() instanceof HitPolicyViolationEvent violation
                        && violation.getOffendingRules().size() > 1);
    if (overlap) {
      return new Outcome.Escalate("conflict");
    }
    if (resolution.getEvaluationStatus() == DecisionEvaluationStatus.FAILED) {
      return new Outcome.Escalate("unhandled");
    }
    // No row matched: the table's default action says so, and the agent takes the case.
    if (!(resolution.getResult() instanceof Map<?, ?> row)
        || row.get("action") == null
        || NO_RULE.equals(text(row.get("action")))) {
      return new Outcome.Escalate("unhandled");
    }
    return new Outcome.Resolved(
        text(row.get("action")), text(row.get("amountBasis")), text(row.get("rule")));
  }

  private static Object first(Object collected) {
    if (collected instanceof Collection<?> items && !items.isEmpty()) {
      return items.iterator().next();
    }
    return collected instanceof Map<?, ?> ? collected : null;
  }

  private static String text(Object value) {
    return value == null ? null : value.toString();
  }
}

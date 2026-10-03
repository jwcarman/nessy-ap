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

import java.util.HashMap;
import java.util.Map;

/**
 * What a case cost, as a whole: per model, the input, output, cache read, cache write and reasoning
 * counts Nessy publishes. A count the vendor never reported is null, never zero: no number is
 * better than a wrong one, and a bare token total would hide what was cached and what was
 * reasoning.
 */
public record Usage(Map<String, Counts> byModel) {

  /** Usage that could not be read at all. */
  public static final Usage UNKNOWN = new Usage(Map.of());

  /** One model's counts; each null when never reported. */
  public record Counts(Long input, Long output, Long cacheRead, Long cacheWrite, Long reasoning) {

    static final Counts NONE = new Counts(null, null, null, null, null);

    Counts minus(Counts before) {
      return new Counts(
          minus(input, before.input),
          minus(output, before.output),
          minus(cacheRead, before.cacheRead),
          minus(cacheWrite, before.cacheWrite),
          minus(reasoning, before.reasoning));
    }

    private static Long minus(Long after, Long before) {
      if (after == null) {
        return null;
      }
      return before == null ? after : after - before;
    }
  }

  public Usage {
    byModel = Map.copyOf(byModel);
  }

  /** What was used between {@code before} and this reading of the same running totals. */
  public Usage since(Usage before) {
    Map<String, Counts> spent = new HashMap<>();
    byModel.forEach(
        (model, counts) -> {
          Counts used = counts.minus(before.byModel.getOrDefault(model, Counts.NONE));
          if (!used.equals(Counts.NONE)) {
            spent.put(model, used);
          }
        });
    return new Usage(spent);
  }
}

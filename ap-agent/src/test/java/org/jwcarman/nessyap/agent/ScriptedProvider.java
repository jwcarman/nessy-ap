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
package org.jwcarman.nessyap.agent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jwcarman.nessy.api.block.Block;
import org.jwcarman.nessy.api.turn.Exchange;
import org.jwcarman.nessy.api.turn.ToolOutcome;
import org.jwcarman.nessy.api.turn.Turn;
import org.jwcarman.nessy.inference.InferenceNarrator;
import org.jwcarman.nessy.inference.InferenceProvider;
import org.jwcarman.nessy.inference.InferenceRequest;
import org.jwcarman.nessy.inference.InferenceResult;

/**
 * A model that does what a test tells it to. Nessy ships no scripted provider (spec §10, F5), so
 * this one is the project's own: a test sets the script, and every request is kept so the test can
 * see what the model was shown.
 */
public final class ScriptedProvider implements InferenceProvider {

  /** Says "Noted." and stops: every turn ends at once. */
  public static final Function<InferenceRequest, InferenceResult> NOTED =
      request -> new InferenceResult.Answer(List.of(new Block.Text("Noted.")));

  private final AtomicReference<Function<InferenceRequest, InferenceResult>> script =
      new AtomicReference<>(NOTED);
  private final List<InferenceRequest> requests = new CopyOnWriteArrayList<>();

  /**
   * A script that takes one step per model call within a turn: the first call of a turn gets the
   * first step, the call after one round of tool results the second, and so on. Past the last step
   * it answers "Done."
   */
  @SafeVarargs
  public static Function<InferenceRequest, InferenceResult> steps(
      Supplier<InferenceResult>... steps) {
    return request -> {
      int step = currentTurn(request).exchanges().size();
      return step < steps.length
          ? steps[step].get()
          : new InferenceResult.Answer(List.of(new Block.Text("Done.")));
    };
  }

  /** Calls one tool. */
  public static Supplier<InferenceResult> call(String id, String tool, String arguments) {
    return () -> new InferenceResult.Actions(List.of(new Block.ToolCall(id, tool, arguments)));
  }

  /** The turn under way in this request. */
  public static Turn currentTurn(InferenceRequest request) {
    return request.context().turns().stream()
        .filter(turn -> !turn.complete())
        .reduce((first, second) -> second)
        .orElseThrow(() -> new IllegalStateException("no turn under way"));
  }

  /** Every tool outcome the model has been shown, across every request so far, in order. */
  public List<ToolOutcome> outcomesSeen() {
    return requests.stream()
        .map(ScriptedProvider::currentTurn)
        .flatMap(turn -> turn.exchanges().stream())
        .map(Exchange::outcomes)
        .flatMap(List::stream)
        .distinct()
        .toList();
  }

  public void script(Function<InferenceRequest, InferenceResult> next) {
    script.set(next);
  }

  public void reset() {
    script.set(NOTED);
    requests.clear();
  }

  public List<InferenceRequest> requests() {
    return List.copyOf(requests);
  }

  @Override
  public InferenceResult infer(InferenceRequest request, InferenceNarrator narrator) {
    requests.add(request);
    return script.get().apply(request);
  }
}

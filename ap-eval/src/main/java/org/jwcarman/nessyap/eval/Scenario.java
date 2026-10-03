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

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One thing the agent is asked to get right.
 *
 * @param erpScenario the ERP seed scenario that sets it up
 * @param expectedAction the resolution a competent AP analyst would reach
 * @param expectedRole the role the routing policy should hand that resolution to
 * @param requiredTools tools the agent must have used before proposing, as evidence
 * @param forbiddenActions actions that are unsafe here even if later withdrawn; proposing one fails
 *     the run's safety check
 * @param mustMail who the agent must have written to ({@code buyer}, {@code vendor}); part of the
 *     evidence
 * @param neverMail who the agent must never have written to; writing to one fails the safety check
 * @param replies what each counterparty ({@code buyer}, {@code vendor}) answers, once, to every
 *     message the desk sends them; a kind with no entry never answers
 */
public record Scenario(
    String name,
    String erpScenario,
    String expectedAction,
    String expectedRole,
    List<String> requiredTools,
    Set<String> forbiddenActions,
    Set<String> mustMail,
    Set<String> neverMail,
    Map<String, String> replies) {

  public Scenario {
    requiredTools = List.copyOf(requiredTools);
    forbiddenActions = Set.copyOf(forbiddenActions);
    mustMail = Set.copyOf(mustMail);
    neverMail = Set.copyOf(neverMail);
    replies = Map.copyOf(replies);
  }
}

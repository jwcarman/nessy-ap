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
package org.jwcarman.nessyap.erp.admin;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.util.pattern.PathPatternParser;

/** The fault rules in force. Held in memory: they are a test fixture, not ERP data. */
@Component
public class FaultRules {

  private final Map<String, FaultRule> rules = new ConcurrentHashMap<>();

  public void put(FaultRule rule) {
    rules.put(rule.pathPattern(), rule);
  }

  public void clear() {
    rules.clear();
  }

  public List<FaultRule> list() {
    return List.copyOf(rules.values());
  }

  public Optional<FaultRule> matching(String path) {
    PathContainer container = PathContainer.parsePath(path);
    return rules.values().stream()
        .filter(
            rule -> PathPatternParser.defaultInstance.parse(rule.pathPattern()).matches(container))
        .findFirst();
  }
}

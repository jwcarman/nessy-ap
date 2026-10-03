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
package org.jwcarman.nessyap.erp.admin;

import org.jwcarman.nessyap.erp.support.InvalidRequestException;

/**
 * Trouble to inject into matching API requests.
 *
 * @param pathPattern a Spring path pattern under {@code /api/}, e.g. {@code /api/vendors/**}
 * @param latencyMillis how long to stall every matching request
 * @param errorRate the share of matching requests answered 503, from 0 to 1
 */
public record FaultRule(String pathPattern, long latencyMillis, double errorRate) {

  public FaultRule {
    if (pathPattern == null || !pathPattern.startsWith("/api/")) {
      throw new InvalidRequestException("Faults apply only under /api/");
    }
    if (latencyMillis < 0) {
      throw new InvalidRequestException("latencyMillis cannot be negative");
    }
    if (errorRate < 0 || errorRate > 1) {
      throw new InvalidRequestException("errorRate must be between 0 and 1");
    }
  }
}

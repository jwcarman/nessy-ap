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

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies the fault rules to API requests. Never to anything outside {@code /api/}, so the admin
 * endpoints that clear the faults always work.
 */
@Component
public class FaultInjectionFilter extends OncePerRequestFilter {

  private static final String INJECTED =
      "{\"status\":503,\"title\":\"Service Unavailable\",\"detail\":\"Injected fault\","
          + "\"code\":\"INJECTED_FAULT\"}";

  private static final String RETRY_AFTER_SECONDS = "2";

  private static final String RATE_LIMITED =
      "{\"status\":429,\"title\":\"Too Many Requests\",\"detail\":\"Injected rate limit\","
          + "\"code\":\"RATE_LIMITED\"}";

  private final FaultRules rules;

  public FaultInjectionFilter(FaultRules rules) {
    this.rules = rules;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/api/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Optional<FaultRule> rule = rules.matching(request.getRequestURI());
    if (rule.isPresent()) {
      stall(rule.get().latencyMillis());
      if (ThreadLocalRandom.current().nextDouble() < rule.get().errorRate()) {
        fail(response, rule.get().status());
        return;
      }
    }
    chain.doFilter(request, response);
  }

  private static void fail(HttpServletResponse response, int status) throws IOException {
    response.setStatus(status);
    response.setContentType("application/problem+json");
    if (status == FaultRule.RATE_LIMITED) {
      response.setHeader("Retry-After", RETRY_AFTER_SECONDS);
      response.getWriter().write(RATE_LIMITED);
    } else {
      response.getWriter().write(INJECTED);
    }
  }

  private static void stall(long millis) {
    if (millis == 0) {
      return;
    }
    try {
      Thread.sleep(Duration.ofMillis(millis));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}

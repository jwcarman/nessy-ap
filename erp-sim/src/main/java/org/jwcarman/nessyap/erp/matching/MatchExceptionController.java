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
package org.jwcarman.nessyap.erp.matching;

import java.util.List;
import java.util.UUID;
import org.jwcarman.nessyap.erp.invoice.InvoiceQueries;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/match-exceptions")
public class MatchExceptionController {

  private final InvoiceQueries queries;

  public MatchExceptionController(InvoiceQueries queries) {
    this.queries = queries;
  }

  @GetMapping
  public List<MatchException> list(@RequestParam(defaultValue = "OPEN") ExceptionStatus status) {
    return queries.exceptions(status);
  }

  @GetMapping("/{id}")
  public MatchException get(@PathVariable UUID id) {
    return queries.exception(id);
  }
}

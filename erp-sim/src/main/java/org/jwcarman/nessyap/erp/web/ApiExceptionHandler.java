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
package org.jwcarman.nessyap.erp.web;

import org.jwcarman.nessyap.erp.support.ApiException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Every {@link ApiException} becomes a problem response carrying its stable {@code code}. */
@RestControllerAdvice
public class ApiExceptionHandler {

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<ProblemDetail> handle(ApiException e) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
    problem.setProperty("code", e.code());
    return ResponseEntity.status(e.status()).body(problem);
  }
}

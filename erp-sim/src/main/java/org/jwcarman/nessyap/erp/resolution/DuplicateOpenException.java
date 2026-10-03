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
package org.jwcarman.nessyap.erp.resolution;

import java.util.UUID;
import org.jwcarman.nessyap.erp.support.ApiException;
import org.springframework.http.HttpStatus;

/** A command that would pay an invoice whose number repeats one already received. */
public class DuplicateOpenException extends ApiException {

  public DuplicateOpenException(UUID invoiceId) {
    super(
        HttpStatus.UNPROCESSABLE_CONTENT,
        "DUPLICATE_OPEN",
        "Invoice "
            + invoiceId
            + " repeats the number of one already received; it cannot be paid, only rejected or"
            + " held");
  }
}

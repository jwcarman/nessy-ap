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
package org.jwcarman.nessyap.erp.resolution;

import java.util.UUID;
import org.jwcarman.nessyap.erp.support.ApiException;
import org.springframework.http.HttpStatus;

public class BankChangeUnverifiedException extends ApiException {

  public BankChangeUnverifiedException(UUID vendorId) {
    super(
        HttpStatus.UNPROCESSABLE_CONTENT,
        "BANK_CHANGE_UNVERIFIED",
        "Vendor "
            + vendorId
            + " has an unverified bank change; no payment can move until it is verified");
  }
}

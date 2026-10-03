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

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Whether the ERP checks who decides ({@code enforce}, the default) or trusts its integration
 * caller to say ({@code trust-integration-user}): the common weak deployment, kept so its cost can
 * be shown.
 */
@Component
public class AuthorityMode {

  public static final String TRUST = "trust-integration-user";

  private final boolean trusting;

  public AuthorityMode(@Value("${erp.authority.mode:enforce}") String mode) {
    this.trusting = TRUST.equals(mode);
  }

  public boolean trustsIntegrationUser() {
    return trusting;
  }
}

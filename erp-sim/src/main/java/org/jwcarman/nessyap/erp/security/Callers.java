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
package org.jwcarman.nessyap.erp.security;

import org.jwcarman.nessyap.erp.audit.Actor;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Who is calling: the client the token was issued to ({@code azp}), and the person it carries, if
 * any. A client's own service-account token carries no person.
 */
public final class Callers {

  private static final String SERVICE_ACCOUNT = "service-account-";

  private Callers() {}

  public static Actor of(Authentication authentication) {
    if (!(authentication instanceof JwtAuthenticationToken token)) {
      return Actor.anonymous();
    }
    String client = token.getToken().getClaimAsString("azp");
    String user = token.getToken().getClaimAsString("preferred_username");
    if (user != null && user.startsWith(SERVICE_ACCOUNT)) {
      user = null;
    }
    return new Actor(client == null ? "unknown" : client, user);
  }
}

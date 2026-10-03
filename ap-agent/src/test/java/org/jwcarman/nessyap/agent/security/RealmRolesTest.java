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
package org.jwcarman.nessyap.agent.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class RealmRolesTest {

  @Test
  void realm_roles_become_role_authorities() {
    Map<String, Object> claims =
        Map.of("realm_access", Map.of("roles", List.of("controller", "offline_access")));

    assertThat(RealmRoles.authorities(claims))
        .extracting(GrantedAuthority::getAuthority)
        .containsExactlyInAnyOrder("ROLE_controller", "ROLE_offline_access");
  }

  @Test
  void no_realm_access_means_no_roles() {
    assertThat(RealmRoles.authorities(Map.of())).isEmpty();
  }

  @Test
  void a_bearer_is_named_by_its_username_not_its_subject() {
    Jwt jwt =
        Jwt.withTokenValue("t")
            .header("alg", "none")
            .subject("8f1c-uuid")
            .claim("preferred_username", "connie")
            .claim("realm_access", Map.of("roles", List.of("controller")))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();

    AbstractAuthenticationToken authentication = RealmRoles.jwtConverter().convert(jwt);

    assertThat(authentication.getName()).isEqualTo("connie");
    assertThat(RealmRoles.of(authentication)).containsExactly("controller");
  }
}

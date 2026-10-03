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

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/**
 * Keycloak's realm roles as Spring authorities: {@code realm_access.roles} becomes {@code
 * ROLE_<role>}. One extractor for both doors, so the workbench and the API agree on who may do
 * what.
 */
public final class RealmRoles {

  private static final String PREFIX = "ROLE_";

  private RealmRoles() {}

  public static Collection<GrantedAuthority> authorities(Map<String, Object> claims) {
    List<GrantedAuthority> authorities = new ArrayList<>();
    if (claims.get("realm_access") instanceof Map<?, ?> access
        && access.get("roles") instanceof Collection<?> roles) {
      for (Object role : roles) {
        authorities.add(new SimpleGrantedAuthority(PREFIX + role));
      }
    }
    return authorities;
  }

  public static Collection<GrantedAuthority> authorities(Jwt jwt) {
    return authorities(jwt.getClaims());
  }

  /** The API's view of a bearer: named by {@code preferred_username}, with its realm roles. */
  public static JwtAuthenticationConverter jwtConverter() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(RealmRoles::authorities);
    converter.setPrincipalClaimName("preferred_username");
    return converter;
  }

  /** The workbench's view of a logged-in user: whatever it had, plus its realm roles. */
  public static Collection<GrantedAuthority> mapOidc(Collection<? extends GrantedAuthority> given) {
    Set<GrantedAuthority> mapped = new LinkedHashSet<>(given);
    for (GrantedAuthority authority : given) {
      if (authority instanceof OidcUserAuthority oidc) {
        mapped.addAll(authorities(oidc.getIdToken().getClaims()));
        if (oidc.getUserInfo() != null) {
          mapped.addAll(authorities(oidc.getUserInfo().getClaims()));
        }
      }
    }
    return List.copyOf(mapped);
  }

  /** The realm role names an authenticated caller holds, without the {@code ROLE_} prefix. */
  public static Set<String> of(Authentication authentication) {
    Set<String> roles = new LinkedHashSet<>();
    for (GrantedAuthority authority : authentication.getAuthorities()) {
      String name = authority.getAuthority();
      if (name != null && name.startsWith(PREFIX)) {
        roles.add(name.substring(PREFIX.length()));
      }
    }
    return roles;
  }
}

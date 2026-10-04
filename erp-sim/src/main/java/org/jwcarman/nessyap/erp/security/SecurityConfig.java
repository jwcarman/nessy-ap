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
package org.jwcarman.nessyap.erp.security;

import java.util.List;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * The ERP trusts nobody by default: every API call carries a token Keycloak signed, issued by this
 * realm, for this ERP. The admin endpoints that seed and reset the simulator stay open (a dev stack
 * has no other profile).
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

  @Bean
  public SecurityFilterChain api(HttpSecurity http) {
    http.authorizeHttpRequests(
            requests ->
                requests
                    .requestMatchers(
                        "/admin/**", "/actuator/health", "/actuator/health/**", "/error")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/**")
                    .authenticated()
                    // Resolutions: the authority matrix (or, in trust mode, the named user)
                    // decides.
                    .requestMatchers(HttpMethod.POST, "/api/invoices/*/*")
                    .authenticated()
                    // Every other write is a person's: a client's own token may only read.
                    .requestMatchers("/api/**")
                    .access(SecurityConfig::aPerson)
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(server -> server.jwt(Customizer.withDefaults()))
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        // The resource server already exempts a request that carries a bearer token from CSRF.
        // The admin endpoints take no token at all: they exist only for development and the
        // evaluation, and no browser session can reach them.
        .csrf(csrf -> csrf.ignoringRequestMatchers("/admin/**"));
    return http.build();
  }

  private static AuthorizationDecision aPerson(
      Supplier<? extends Authentication> authentication, RequestAuthorizationContext request) {
    return new AuthorizationDecision(Callers.of(authentication.get()).user() != null);
  }

  /** Signature from the realm's keys; issuer this realm; audience must include this ERP. */
  @Bean
  public JwtDecoder jwtDecoder(
      @Value("${erp.security.jwk-set-uri}") String jwkSetUri,
      @Value("${erp.security.issuer}") String issuer,
      @Value("${erp.security.audience:erp-sim}") String audience) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer),
            new JwtClaimValidator<List<String>>(
                "aud", audiences -> audiences != null && audiences.contains(audience))));
    return decoder;
  }
}

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
package org.jwcarman.nessyap.agent.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Two doors, two kinds of credential. People use the workbench through a browser and log in with
 * Keycloak; programs (the evaluation, later the agent's own tools) call {@code /api/**} with a
 * bearer token. Health and metrics stay open (a dev-stack choice for the evaluation: read-only
 * telemetry, which production would put behind its own credential).
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

  @Bean
  @Order(1)
  public SecurityFilterChain api(HttpSecurity http) {
    http.securityMatcher("/api/**")
        .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
        .oauth2ResourceServer(
            server -> server.jwt(jwt -> jwt.jwtAuthenticationConverter(RealmRoles.jwtConverter())))
        // CSRF stays on. The resource server already exempts a request that carries a bearer
        // token: it has no session cookie for a forged cross-site request to ride on.
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
    return http.build();
  }

  @Bean
  @Order(2)
  public SecurityFilterChain workbench(
      HttpSecurity http, ClientRegistrationRepository registrations) {
    DefaultOAuth2AuthorizationRequestResolver authorizationRequests =
        new DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization");
    authorizationRequests.setAuthorizationRequestCustomizer(
        OAuth2AuthorizationRequestCustomizers.withPkce());
    OidcClientInitiatedLogoutSuccessHandler loggedOut =
        new OidcClientInitiatedLogoutSuccessHandler(registrations);
    loggedOut.setPostLogoutRedirectUri("{baseUrl}/workbench");
    http.authorizeHttpRequests(
            requests ->
                requests
                    .requestMatchers(
                        "/actuator/health",
                        "/actuator/health/**",
                        "/actuator/metrics/**",
                        "/error",
                        "/workbench.css")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2Login(
            login ->
                login
                    .authorizationEndpoint(
                        endpoint -> endpoint.authorizationRequestResolver(authorizationRequests))
                    .userInfoEndpoint(
                        userInfo -> userInfo.userAuthoritiesMapper(RealmRoles::mapOidc)))
        .logout(logout -> logout.logoutSuccessHandler(loggedOut));
    return http.build();
  }
}

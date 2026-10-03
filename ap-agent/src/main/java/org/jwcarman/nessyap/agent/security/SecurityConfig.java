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
 * bearer token. Case reads and health stay open for the evaluation's polling.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

  @Bean
  @Order(1)
  public SecurityFilterChain api(HttpSecurity http) throws Exception {
    http.securityMatcher("/api/**")
        .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
        .oauth2ResourceServer(
            server -> server.jwt(jwt -> jwt.jwtAuthenticationConverter(RealmRoles.jwtConverter())))
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(csrf -> csrf.disable());
    return http.build();
  }

  @Bean
  @Order(2)
  public SecurityFilterChain workbench(
      HttpSecurity http, ClientRegistrationRepository registrations) throws Exception {
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
                        "/cases/**",
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

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

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * A stand-in identity provider: a fresh RSA key, its public half served as a JWKS over real HTTP,
 * and real signed tokens. The ERP validates them exactly as it validates Keycloak's.
 */
public final class SignedTokens implements AutoCloseable {

  public static final String ISSUER = "http://keycloak.test/realms/nessy-ap";

  private final RSAKey key;
  private final NimbusJwtEncoder encoder;
  private final HttpServer jwks;

  public SignedTokens() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      var pair = generator.generateKeyPair();
      key =
          new RSAKey.Builder((RSAPublicKey) pair.getPublic())
              .privateKey((RSAPrivateKey) pair.getPrivate())
              .keyID("test")
              .build();
      encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
      byte[] published = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
      jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      jwks.createContext(
          "/certs",
          exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, published.length);
            try (OutputStream out = exchange.getResponseBody()) {
              out.write(published);
            }
          });
      jwks.start();
    } catch (NoSuchAlgorithmException | IOException e) {
      throw new IllegalStateException("cannot set up test tokens", e);
    }
  }

  public String jwkSetUri() {
    return "http://127.0.0.1:" + jwks.getAddress().getPort() + "/certs";
  }

  /** A person's token, issued to {@code client}, for {@code audience}. */
  public String user(String username, String client, String audience) {
    return sign(username, client, audience, username);
  }

  /** A client's own token, as Keycloak issues for a service account. */
  public String service(String client) {
    return sign("service-account-" + client, client, "erp-sim", "service-account-" + client);
  }

  private String sign(String subject, String client, String audience, String username) {
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(ISSUER)
            .subject(subject)
            .audience(List.of(audience))
            .claim("azp", client)
            .claim("preferred_username", username)
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(300))
            .build();
    return encoder
        .encode(
            JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).keyId("test").build(), claims))
        .getTokenValue();
  }

  @Override
  public void close() {
    jwks.stop(0);
  }
}

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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import javax.crypto.spec.SecretKeySpec;
import org.jwcarman.codec.crypto.EnvelopeCodec;
import org.jwcarman.codec.crypto.JceDataKeyProvider;
import org.jwcarman.nessy.api.StorageCodecConfigurer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Everything Nessy stores is encrypted at rest: events, messages, tool results, effects and the
 * backlog. Nessy's history holds what agents were shown, which includes the text of vendor mail
 * that the quarantined reader read, so it is protected like Occlude's own store: AES-256-GCM
 * envelope encryption from codec-crypto, under a key-encryption key of its own.
 *
 * <p>The key provider is deliberately not a bean. Occlude backs off from its own provider when the
 * context holds one, and its store must stay under its own key.
 *
 * <p>Nothing is compressed before it is encrypted: the stored text mixes attacker-written mail with
 * the desk's own data, and compressing the two together leaks through the length (CRIME/BREACH).
 */
@Configuration(proxyBeanMethods = false)
public class NessyStorageEncryption {

  /**
   * Binds every stored row to this purpose, so a row from another store under the key will not
   * read.
   */
  private static final byte[] PURPOSE = "nessy-ap/nessy-storage".getBytes(StandardCharsets.UTF_8);

  @Bean
  StorageCodecConfigurer encryptedStorage(
      @Value("${ap.nessy-storage.key-id}") String keyId,
      @Value("${ap.nessy-storage.kek}") String kek) {
    JceDataKeyProvider keys =
        new JceDataKeyProvider(
            keyId, Map.of(keyId, new SecretKeySpec(Base64.getDecoder().decode(kek), "AES")));
    EnvelopeCodec envelope = EnvelopeCodec.builder(keys).aad(PURPOSE).build();
    return original -> original.andThen(envelope);
  }
}

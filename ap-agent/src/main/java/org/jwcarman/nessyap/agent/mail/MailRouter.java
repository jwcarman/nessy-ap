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
package org.jwcarman.nessyap.agent.mail;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jwcarman.nessyap.agent.cases.Cases;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Which case a message is about: the case token in its subject first, then any message of ours it
 * says it answers ({@code In-Reply-To}, then {@code References}).
 */
@Component
public class MailRouter {

  private static final Pattern TOKEN = Pattern.compile("\\[AP ([0-9a-fA-F-]{36})\\]");

  private final Cases cases;
  private final JdbcClient jdbc;

  public MailRouter(Cases cases, JdbcClient jdbc) {
    this.cases = cases;
    this.jdbc = jdbc;
  }

  public Optional<UUID> route(Message message) throws MessagingException {
    Optional<UUID> byToken = byToken(message.getSubject());
    if (byToken.isPresent()) {
      return byToken;
    }
    for (String answered : answered(message)) {
      Optional<UUID> found =
          jdbc.sql("select exception_id from outbound_mail where message_id = :id")
              .param("id", answered)
              .query(UUID.class)
              .optional();
      if (found.isPresent()) {
        return found;
      }
    }
    return Optional.empty();
  }

  private Optional<UUID> byToken(String subject) {
    if (subject == null) {
      return Optional.empty();
    }
    Matcher matcher = TOKEN.matcher(subject);
    while (matcher.find()) {
      try {
        UUID exceptionId = UUID.fromString(matcher.group(1));
        if (cases.find(exceptionId).isPresent()) {
          return Optional.of(exceptionId);
        }
      } catch (IllegalArgumentException _) {
        // A token-shaped string that is not a UUID routes nowhere; try the next one.
      }
    }
    return Optional.empty();
  }

  private static List<String> answered(Message message) throws MessagingException {
    List<String> ids = new ArrayList<>();
    for (String header : List.of("In-Reply-To", "References")) {
      String[] values = message.getHeader(header);
      if (values != null) {
        for (String value : values) {
          for (String id : value.trim().split("\\s+")) {
            if (!id.isBlank()) {
              ids.add(id);
            }
          }
        }
      }
    }
    return ids;
  }
}

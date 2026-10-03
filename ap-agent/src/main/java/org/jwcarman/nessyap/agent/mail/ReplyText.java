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
package org.jwcarman.nessyap.agent.mail;

import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import java.io.IOException;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * What the agent reads of a reply: the plain-text part (or the HTML part stripped to text when
 * there is none), without the quoted history, and never more than {@value #MAX} characters.
 */
public final class ReplyText {

  static final int MAX = 4_000;

  private static final Pattern TAG = Pattern.compile("<[^>]*>");

  private ReplyText() {}

  public static String of(Part message) throws MessagingException, IOException {
    String text =
        find(message, "text/plain").or(() -> html(message)).orElse("(the message had no text)");
    return clean(text);
  }

  /** Drops quoted lines ({@code >}) and trailing blank space, and caps the length. */
  public static String clean(String text) {
    String kept =
        text.lines()
            .filter(line -> !line.stripLeading().startsWith(">"))
            .collect(Collectors.joining("\n"))
            .strip();
    return kept.length() <= MAX ? kept : kept.substring(0, MAX);
  }

  private static Optional<String> html(Part message) {
    try {
      return find(message, "text/html")
          .map(
              html ->
                  TAG.matcher(html.replaceAll("(?i)<br\\s*/?>|</p>", "\n"))
                      .replaceAll("")
                      .replace("&nbsp;", " ")
                      .replace("&lt;", "<")
                      .replace("&gt;", ">")
                      .replace("&amp;", "&"));
    } catch (MessagingException | IOException e) {
      return Optional.empty();
    }
  }

  private static Optional<String> find(Part part, String type)
      throws MessagingException, IOException {
    if (part.isMimeType(type)) {
      return Optional.of(part.getContent().toString());
    }
    if (part.isMimeType("multipart/*") && part.getContent() instanceof Multipart multipart) {
      for (int i = 0; i < multipart.getCount(); i++) {
        BodyPart child = multipart.getBodyPart(i);
        Optional<String> found = find(child, type);
        if (found.isPresent()) {
          return found;
        }
      }
    }
    return Optional.empty();
  }
}

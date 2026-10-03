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

import static org.awaitility.Awaitility.await;

import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.testcontainers.containers.GenericContainer;

/**
 * Reads what GreenMail delivered, as the recipient would. GreenMail creates a mailbox on first
 * delivery, with the address as both login and password.
 */
public final class Mailbox {

  private final GenericContainer<?> greenMail;

  public Mailbox(GenericContainer<?> greenMail) {
    this.greenMail = greenMail;
  }

  /** Empties every mailbox, so each test starts with nothing delivered. */
  public void purgeAll() {
    URI purge =
        URI.create(
            "http://"
                + greenMail.getHost()
                + ":"
                + greenMail.getMappedPort(8080)
                + "/api/mail/purge");
    try (HttpClient http = HttpClient.newHttpClient()) {
      http.send(
          HttpRequest.newBuilder(purge).POST(HttpRequest.BodyPublishers.noBody()).build(),
          HttpResponse.BodyHandlers.discarding());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** Waits for exactly one message in the address's inbox and returns a detached copy. */
  public Message awaitOne(String address) {
    return await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> read(address), messages -> messages.size() == 1)
        .getFirst();
  }

  /** Every message in the address's inbox, detached so it stays readable after the store closes. */
  public List<Message> read(String address) throws MessagingException {
    Store store = Session.getInstance(new Properties()).getStore("imap");
    try {
      store.connect(greenMail.getHost(), greenMail.getMappedPort(3143), address, address);
    } catch (MessagingException noMailboxYet) {
      return List.of();
    }
    try (store) {
      Folder inbox = store.getFolder("INBOX");
      inbox.open(Folder.READ_ONLY);
      List<Message> copies = new ArrayList<>();
      for (Message message : inbox.getMessages()) {
        copies.add(new MimeMessage((MimeMessage) message));
      }
      inbox.close(false);
      return copies;
    }
  }

  /** How many messages in the address's inbox nobody has read yet. */
  public int unseen(String address) throws MessagingException {
    Store store = Session.getInstance(new Properties()).getStore("imap");
    store.connect(greenMail.getHost(), greenMail.getMappedPort(3143), address, address);
    try (store) {
      Folder inbox = store.getFolder("INBOX");
      inbox.open(Folder.READ_ONLY);
      int count = inbox.getUnreadMessageCount();
      inbox.close(false);
      return count;
    }
  }

  /** Marks every message in the address's inbox unseen again, as if nobody had read it. */
  public void markAllUnseen(String address) throws MessagingException {
    Store store = Session.getInstance(new Properties()).getStore("imap");
    store.connect(greenMail.getHost(), greenMail.getMappedPort(3143), address, address);
    try (store) {
      Folder inbox = store.getFolder("INBOX");
      inbox.open(Folder.READ_WRITE);
      inbox.setFlags(inbox.getMessages(), new Flags(Flags.Flag.SEEN), false);
      inbox.close(false);
    }
  }
}

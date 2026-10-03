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
package org.jwcarman.nessyap.agent.quarantine;

import java.util.List;
import org.jwcarman.nessyap.agent.quarantine.Untrusted.Intent;
import org.jwcarman.nessyap.agent.quarantine.Untrusted.Reply;
import org.jwcarman.nessyap.agent.quarantine.Untrusted.ReplyReading;

/**
 * Reads a reply into a typed reading. It sees untrusted text, so it must have no tools and no
 * authority: its only output is the reading.
 */
public interface ReplyReader {

  ReplyReading read(Reply reply);

  /**
   * What the desk assumes when a reply cannot be read: nothing it says is known, and a person must
   * read it.
   */
  static ReplyReading unread(Reply reply) {
    return new ReplyReading(reply.vendorId(), Intent.OTHER, List.of(), null, null, true);
  }
}

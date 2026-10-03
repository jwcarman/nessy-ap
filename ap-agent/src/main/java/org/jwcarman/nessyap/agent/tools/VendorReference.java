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
package org.jwcarman.nessyap.agent.tools;

import java.util.regex.Pattern;

/**
 * A reference a vendor wrote, such as its invoice number or the PO number it quotes, as the agent
 * may see it. Shaped like a reference, it is shown; anything else is withheld, because it may be a
 * sentence written to the agent. The ERP keeps the original, and people read it there.
 */
public final class VendorReference {

  /** Letters, digits, spaces and the punctuation references use, at most 32 characters. */
  private static final Pattern SHAPE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 ./_#-]{0,31}");

  static final String WITHHELD =
      "(withheld: not shaped like a reference; the vendor wrote it, people can read it in the ERP)";

  private VendorReference() {}

  /** The reference, or a note that it was withheld; null stays null. */
  public static String shown(String written) {
    if (written == null) {
      return null;
    }
    return SHAPE.matcher(written).matches() ? written : WITHHELD;
  }
}

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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.regex.Pattern;

/**
 * A purchase-order number in the shape the ERP issues. A value that does not fit cannot be made, so
 * a model's answer with a bad number fails to read instead of carrying text through. On the wire it
 * is a bare string, and Nessy's schema generator describes it to the model as one.
 *
 * @param value the number, for example {@code PO-9E7F1264}
 */
public record PoNumber(@JsonValue String value) {

  private static final Pattern SHAPE = Pattern.compile("PO-[A-Z0-9-]{1,32}");

  public PoNumber {
    if (value == null || !SHAPE.matcher(value).matches()) {
      throw new IllegalArgumentException("not a purchase-order number: " + value);
    }
  }

  @JsonCreator
  public static PoNumber of(String value) {
    return new PoNumber(value);
  }
}

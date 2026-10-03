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
package org.jwcarman.nessyap.erp.audit;

/**
 * Who is making a change: the calling client and, when it acts for a person, that person.
 *
 * <p>Until identity arrives (slice 4) every API caller is {@link #anonymous()}.
 *
 * @param user null when the client acts for itself
 */
public record Actor(String client, String user) {

  public static Actor anonymous() {
    return new Actor("anonymous", null);
  }

  /** The ERP itself, e.g. loading a seed scenario. */
  public static Actor system() {
    return new Actor("erp-sim", null);
  }
}

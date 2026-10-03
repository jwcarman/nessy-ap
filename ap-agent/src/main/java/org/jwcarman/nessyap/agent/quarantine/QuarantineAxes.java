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

import org.jwcarman.occlude.lattice.Axes;
import org.jwcarman.occlude.lattice.Axis;

/**
 * What the desk asks about every value it holds: has something it already trusts agreed with it?
 * Untrusted text is the more constrained end, so vouching for a value moves it down the ladder.
 */
public final class QuarantineAxes {

  /** Whether something already trusted has agreed with the value. */
  public enum Integrity {
    /** Something already trusted has agreed with it. */
    ENDORSED,
    /** Nothing trusted has vouched for it. */
    UNENDORSED
  }

  public static final Axis<Integrity> INTEGRITY =
      Axis.ladder("integrity", Integrity.ENDORSED, Integrity.UNENDORSED);

  private QuarantineAxes() {}

  public static Axes axes() {
    return Axes.of(INTEGRITY);
  }
}

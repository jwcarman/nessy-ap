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
package org.jwcarman.nessyap.eval;

import java.util.ArrayList;
import java.util.List;

/**
 * The order runs are made in. Cases run side by side, except those whose trouble is set up for the
 * whole ERP: a scenario that breaks the ERP's reads would break every case beside it, so it runs
 * alone, after the rest.
 *
 * @param together the runs made side by side, in report order
 * @param alone the runs made one at a time, afterwards
 */
record Schedule(List<Run> together, List<Run> alone) {

  /** One run of one scenario. */
  record Run(Scenario scenario, int repetition) {}

  Schedule {
    together = List.copyOf(together);
    alone = List.copyOf(alone);
  }

  static Schedule of(List<Scenario> scenarios, int repetitions) {
    List<Run> together = new ArrayList<>();
    List<Run> alone = new ArrayList<>();
    for (Scenario scenario : scenarios) {
      for (int i = 1; i <= repetitions; i++) {
        (scenario.twist() == Scenario.Twist.FLAKY_ERP ? alone : together).add(new Run(scenario, i));
      }
    }
    return new Schedule(together, alone);
  }
}

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
package org.jwcarman.nessyap.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import org.apache.camel.CamelContext;
import org.apache.camel.util.concurrent.ThreadType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Camel's thread pools run on virtual threads, set the way Camel's own configuration means. */
class CamelVirtualThreadsTest extends ApAgentIntegrationTest {

  @Autowired CamelContext camel;

  @Test
  void camel_believes_its_threads_are_virtual() {
    assertThat(ThreadType.current()).isEqualTo(ThreadType.VIRTUAL);
  }

  @Test
  void a_camel_thread_pool_runs_its_work_on_a_virtual_thread() throws Exception {
    ExecutorService pool =
        camel.getExecutorServiceManager().newDefaultThreadPool(this, "virtual-threads-probe");
    try {
      Future<Boolean> virtual = pool.submit(() -> Thread.currentThread().isVirtual());

      assertThat(virtual.get()).isTrue();
    } finally {
      camel.getExecutorServiceManager().shutdownNow(pool);
    }
  }
}

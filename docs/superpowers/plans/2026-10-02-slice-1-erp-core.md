# Slice 1: ERP Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up `erp-sim`, the simulated ERP: vendors, purchase orders, goods receipts and invoices in Postgres, a three-way matching engine that raises exceptions, resolution commands with idempotency keys and optimistic versions, an outbox that publishes events to RabbitMQ, named seed scenarios, and fault injection, all reachable over REST.

**Architecture:** A Maven multi-module repo (`ap-contracts` for the event types every service shares, `erp-sim` for the ERP). `erp-sim` is a Spring Boot 4 app over Postgres using `JdbcClient` and immutable records (no JPA), with Liquibase owning the schema (formatted-SQL changesets included from a YAML master changelog). Every write records an audit row and, where the world should hear about it, an outbox row in the same transaction; a scheduled publisher moves outbox rows to a RabbitMQ topic exchange with publisher confirms.

**Tech Stack:** Java 25, Spring Boot 4.1.1 (webmvc, jdbc, liquibase, amqp, actuator), Postgres 18, RabbitMQ 4, Jackson 3 (`tools.jackson`), JUG 5.2.0 for UUIDv7, JUnit 5 + AssertJ + Awaitility, Testcontainers 2.0.5.

**Spec:** `docs/superpowers/specs/2026-10-02-ap-exception-desk-design.md` (§1, §2, §11 slice 1). Nessy is not used in this slice.

## Global Constraints

- Java 25; parent `org.springframework.boot:spring-boot-starter-parent:4.1.1`; groupId `org.jwcarman.nessyap`; version `0.1.0-SNAPSHOT`.
- Base packages: `org.jwcarman.nessyap.contracts` (ap-contracts), `org.jwcarman.nessyap.erp` (erp-sim).
- No star imports, no fully-qualified type names in code, no warning suppression of any kind.
- Every Java file carries the Apache header (`./mvnw license:format`); formatting is google-java-format (`./mvnw spotless:apply`). Run both before every build: `spotless:check` fails before compilation with no compiler output.
- XML comments must not contain `--` (it is a parse error in pom.xml).
- Ids are UUIDv7 from `Ids.next()`; never `UUID.randomUUID()` in main code.
- Money is `BigDecimal`, `numeric(14,2)`; quantities `numeric(14,3)`. Compare with `compareTo` / AssertJ `isEqualByComparingTo`, never `equals`.
- Enums are stored as their `name()` text.
- No mocking library. Integration tests run against real Postgres and RabbitMQ via Testcontainers; Docker must be running.
- Tests: snake_case method names, `@Nested` classes named as `Capitalized_phrases`, display names from `src/test/resources/junit-platform.properties`.
- Exception-assertion lambdas contain exactly ONE call that can throw; do all setup outside the lambda.
- Assert a collection is non-empty before any all/none-match assertion on it.
- Iterate with `./mvnw -q -pl :erp-sim -am test -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false`; run `./mvnw -q clean verify` once per task before its last commit. Check the exit code, never grep Maven output for success.
- Never run two Maven processes at once in one worktree.
- Commit messages end with:
  ```
  Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01AFNcEjRLnMqMPkEMiki84C
  ```

## Review Focus

1. **An invoice line citing a PO line number the PO does not have** must become an `UNPLANNED_CHARGE` finding, never an exception or a 500. Pinned in Task 4.
2. **Two requests with the same `Idempotency-Key` at the same moment** must apply the command once and both get the same answer. Pinned in Task 8.
3. **RabbitMQ down while the outbox publishes** must leave events waiting in the outbox (published later) without failing any request. Pinned in Task 6.
4. **Fault injection must never touch `/admin/**`**, or a fault rule could lock you out of clearing it. Pinned in Task 10.
5. **A vendor with an unverified bank change**: approving, short-paying or releasing a hold on its invoices is refused even with a correct version. Pinned in Task 8.

## File Map

```
pom.xml                                   root: parent, modules, plugins
compose.yaml, compose/postgres-init.sql   local Postgres (erp, apagent, keycloak dbs) + RabbitMQ
CLAUDE.md, README.md, .gitignore, mvnw, .mvn/
ap-contracts/
  src/main/java/org/jwcarman/nessyap/contracts/
    ReasonCode, ErpEvent, MatchExceptionRaised, ReceiptPosted, InvoiceResolved,
    VendorBankChangeProposed, ErpEvents
erp-sim/
  src/main/resources/application.yaml, db/changelog/db.changelog-master.yaml, db/changelog/001-erp-schema.sql
  src/main/java/org/jwcarman/nessyap/erp/
    ErpSimApplication
    support/   Ids, TimeConfig, ApiException, NotFoundException, InvalidRequestException, Fingerprints, ErpReset
    audit/     Actor, AuditLog
    outbox/    Outbox, OutboxPublisher, RabbitConfig
    vendor/    Contact, BankAccount, BankAccountStatus, Vendor, NewVendor, BankChangeProposal,
               VendorRepository, VendorMaster, VendorController
    po/        PoLine, PurchaseOrder, NewPurchaseOrder, ReceiptLine, GoodsReceipt, NewReceipt,
               PurchaseOrderRepository, ReceiptRepository, PurchaseOrders, GoodsReceipts,
               PurchaseOrderController
    matching/  Tolerances, MatchLine, PriorInvoice, MatchInput, MatchFinding, MatchEngine,
               InvoiceNumbers, MatchingConfig, ExceptionStatus, MatchException,
               MatchExceptionRepository, MatchExceptionController
    invoice/   InvoiceStatus, InvoiceLine, Invoice, NewInvoice, InvoiceRepository, InvoiceIntake,
               InvoiceView, InvoiceQueries, InvoiceController
    resolution/ ResolutionAction, ResolutionCommand, StaleVersionException,
               InvalidTransitionException, BankChangeUnverifiedException,
               IdempotencyKeyReusedException, Idempotency, Resolutions, ResolutionController
    admin/     ScenarioResult, ScenarioCatalog, AdminController, FaultRule, FaultRules,
               FaultInjectionFilter, FaultController
    web/       ApiExceptionHandler
  src/test/java/org/jwcarman/nessyap/erp/
    ErpContainers, ErpIntegrationTest, TestData, ... one test class per unit
```

---

### Task 1: Build skeleton, Compose stack, and a booting `erp-sim`

**Files:**
- Create: `pom.xml`, `erp-sim/pom.xml`, `.gitignore`, `compose.yaml`, `compose/postgres-init.sql`, `CLAUDE.md`, `README.md`
- Copy: `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` from `~/IdeaProjects/nessy`
- Create: `erp-sim/src/main/java/org/jwcarman/nessyap/erp/ErpSimApplication.java`
- Create: `erp-sim/src/main/resources/application.yaml`
- Create: `erp-sim/src/main/resources/db/changelog/db.changelog-master.yaml` (empty changelog; Task 2 adds the first include)
- Test: `erp-sim/src/test/java/org/jwcarman/nessyap/erp/ErpContainers.java`, `erp-sim/src/test/java/org/jwcarman/nessyap/erp/ErpSimApplicationTest.java`, `erp-sim/src/test/resources/junit-platform.properties`

**Interfaces:**
- Produces: `ErpContainers` (a `@TestConfiguration` with Postgres and RabbitMQ `@ServiceConnection` beans) that every later integration test imports; Maven module `:erp-sim`.

- [ ] **Step 1: Copy the Maven wrapper and write the root files**

```bash
cd ~/IdeaProjects/nessy-ap
cp ~/IdeaProjects/nessy/mvnw ~/IdeaProjects/nessy/mvnw.cmd .
mkdir -p .mvn/wrapper && cp ~/IdeaProjects/nessy/.mvn/wrapper/maven-wrapper.properties .mvn/wrapper/
```

`.gitignore`:

```
target/
*.iml
.idea/
.DS_Store
.superpowers/
.worktrees/
.claude/worktrees/
```

`pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.1</version>
    <relativePath/>
  </parent>

  <groupId>org.jwcarman.nessyap</groupId>
  <artifactId>nessy-ap</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <packaging>pom</packaging>
  <name>Nessy AP</name>

  <modules>
    <module>erp-sim</module>
  </modules>

  <properties>
    <java.version>25</java.version>
    <nessy.version>0.4.0-SNAPSHOT</nessy.version>
    <jug.version>5.2.0</jug.version>
    <testcontainers.version>2.0.5</testcontainers.version>
    <spotless.version>3.10.2</spotless.version>
    <license.plugin.version>5.1.2</license.plugin.version>
    <license.owner>James Carman</license.owner>
  </properties>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>testcontainers-bom</artifactId>
        <version>${testcontainers.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
      <dependency>
        <groupId>com.fasterxml.uuid</groupId>
        <artifactId>java-uuid-generator</artifactId>
        <version>${jug.version}</version>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <build>
    <plugins>
      <plugin>
        <groupId>com.diffplug.spotless</groupId>
        <artifactId>spotless-maven-plugin</artifactId>
        <version>${spotless.version}</version>
        <configuration>
          <java>
            <googleJavaFormat>
              <style>GOOGLE</style>
            </googleJavaFormat>
          </java>
        </configuration>
        <executions>
          <execution>
            <goals>
              <goal>check</goal>
            </goals>
            <phase>validate</phase>
          </execution>
        </executions>
      </plugin>
      <plugin>
        <groupId>com.mycila</groupId>
        <artifactId>license-maven-plugin</artifactId>
        <version>${license.plugin.version}</version>
        <configuration>
          <properties>
            <owner>${license.owner}</owner>
          </properties>
          <licenseSets>
            <licenseSet>
              <inlineHeader><![CDATA[Copyright © ${year} ${owner}

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.]]></inlineHeader>
              <excludes>
                <exclude>**/*.md</exclude>
                <exclude>**/*.txt</exclude>
                <exclude>**/*.sh</exclude>
                <exclude>**/*.yaml</exclude>
                <exclude>**/*.sql</exclude>
                <exclude>src/main/resources/**</exclude>
                <exclude>src/test/resources/**</exclude>
                <exclude>docs/**</exclude>
                <exclude>compose/**</exclude>
                <exclude>.mvn/**</exclude>
                <exclude>mvnw</exclude>
                <exclude>mvnw.cmd</exclude>
                <exclude>.gitignore</exclude>
                <exclude>.worktrees/**</exclude>
                <exclude>.claude/**</exclude>
                <exclude>.superpowers/**</exclude>
              </excludes>
            </licenseSet>
          </licenseSets>
        </configuration>
        <executions>
          <execution>
            <goals>
              <goal>check</goal>
            </goals>
            <phase>validate</phase>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

`compose.yaml`:

```yaml
name: nessy-ap
services:
  postgres:
    image: postgres:18-alpine
    environment:
      POSTGRES_USER: nessyap
      POSTGRES_PASSWORD: nessyap
    ports:
      - "55432:5432"
    volumes:
      - ./compose/postgres-init.sql:/docker-entrypoint-initdb.d/init.sql:ro
      - postgres-data:/var/lib/postgresql
  rabbitmq:
    image: rabbitmq:4-management-alpine
    environment:
      RABBITMQ_DEFAULT_USER: nessyap
      RABBITMQ_DEFAULT_PASS: nessyap
    ports:
      - "55672:5672"
      - "55673:15672"
volumes:
  postgres-data:
```

`compose/postgres-init.sql`:

```sql
create database erp;
create database apagent;
create database keycloak;
```

`CLAUDE.md`:

```markdown
# nessy-ap rules

- Spec of record: `docs/superpowers/specs/2026-10-02-ap-exception-desk-design.md`.
  Anything awkward or missing in Nessy goes in its §10 findings log; never patch
  around Nessy silently.
- Nessy is consumed as the local `0.4.0-SNAPSHOT`. After any Nessy change, run
  `./mvnw install -DskipTests` in `~/IdeaProjects/nessy` before building here, or a
  stale jar in `~/.m2` shadows the source.
- Before any build: `./mvnw spotless:apply license:format`. Iterate with
  `./mvnw -q -pl :<artifactId> -am test`; run `./mvnw -q clean verify` once per task
  before its last commit. Check exit codes, never grep Maven output.
- Tests need Docker (Testcontainers). To run the apps: `docker compose up -d`
  (Postgres on 55432, RabbitMQ on 55672, management UI on 55673).
- Model policy: the table in `~/IdeaProjects/nessy/CLAUDE.md` applies here too.
```

`README.md`:

```markdown
# nessy-ap

A proving ground for building enterprise agentic applications on
[Nessy](https://github.com/jwcarman/nessy): an accounts-payable exception desk
working against a simulated ERP.

Design: `docs/superpowers/specs/2026-10-02-ap-exception-desk-design.md`.

## Run

    docker compose up -d
    ./mvnw -pl :erp-sim spring-boot:run

`erp-sim` listens on http://localhost:8081.
```

- [ ] **Step 2: Write `erp-sim/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.jwcarman.nessyap</groupId>
    <artifactId>nessy-ap</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </parent>

  <artifactId>erp-sim</artifactId>
  <name>Nessy AP :: ERP simulator</name>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-webmvc</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-jdbc</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-liquibase</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-amqp</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
      <groupId>org.postgresql</groupId>
      <artifactId>postgresql</artifactId>
      <scope>runtime</scope>
    </dependency>
    <dependency>
      <groupId>com.fasterxml.uuid</groupId>
      <artifactId>java-uuid-generator</artifactId>
    </dependency>

    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-testcontainers</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>testcontainers-postgresql</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>testcontainers-rabbitmq</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.awaitility</groupId>
      <artifactId>awaitility</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 3: Write the failing context test and its container configuration**

`erp-sim/src/test/resources/junit-platform.properties`:

```properties
junit.jupiter.displayname.generator.default=org.junit.jupiter.api.DisplayNameGenerator$ReplaceUnderscores
```

`erp-sim/src/test/java/org/jwcarman/nessyap/erp/ErpContainers.java`:

```java
package org.jwcarman.nessyap.erp;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * The real Postgres and RabbitMQ every integration test runs against. One Spring context, so one
 * pair of containers, is shared by every test class that imports this unchanged.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ErpContainers {

  @Bean
  @ServiceConnection
  PostgreSQLContainer postgres() {
    return new PostgreSQLContainer("postgres:18-alpine");
  }

  @Bean
  @ServiceConnection
  RabbitMQContainer rabbit() {
    return new RabbitMQContainer("rabbitmq:4-management-alpine");
  }
}
```

If the context fails with a message that no `ConnectionDetailsFactory` exists for `RabbitMQContainer`, Boot does not recognise the Testcontainers 2 class. Then remove `@ServiceConnection` from `rabbit()` and add this bean instead (imports `org.springframework.test.context.DynamicPropertyRegistrar`):

```java
  @Bean
  DynamicPropertyRegistrar rabbitProperties(RabbitMQContainer rabbit) {
    return registry -> {
      registry.add("spring.rabbitmq.host", rabbit::getHost);
      registry.add("spring.rabbitmq.port", rabbit::getAmqpPort);
      registry.add("spring.rabbitmq.username", rabbit::getAdminUsername);
      registry.add("spring.rabbitmq.password", rabbit::getAdminPassword);
    };
  }
```

`erp-sim/src/test/java/org/jwcarman/nessyap/erp/ErpSimApplicationTest.java`:

```java
package org.jwcarman.nessyap.erp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(ErpContainers.class)
class ErpSimApplicationTest {

  @Autowired JdbcClient jdbc;
  @Autowired RabbitTemplate rabbit;

  @Test
  void boots_against_a_real_database() {
    assertThat(jdbc.sql("select 1").query(Integer.class).single()).isEqualTo(1);
  }

  @Test
  void boots_against_a_real_broker() {
    Boolean open = rabbit.execute(channel -> channel.isOpen());
    assertThat(open).isTrue();
  }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `./mvnw -q spotless:apply license:format && ./mvnw -q -pl :erp-sim -am test`
Expected: FAIL to compile: `ErpSimApplication` does not exist, so `@SpringBootTest` finds no configuration.

- [ ] **Step 5: Write the application and its configuration**

`erp-sim/src/main/java/org/jwcarman/nessyap/erp/ErpSimApplication.java`:

```java
package org.jwcarman.nessyap.erp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** The simulated ERP: the system of record the AP agent investigates and people decide against. */
@SpringBootApplication
@EnableScheduling
public class ErpSimApplication {

  public static void main(String[] args) {
    SpringApplication.run(ErpSimApplication.class, args);
  }
}
```

`erp-sim/src/main/resources/application.yaml`:

```yaml
server:
  port: 8081
spring:
  application:
    name: erp-sim
  datasource:
    url: jdbc:postgresql://localhost:55432/erp
    username: nessyap
    password: nessyap
  rabbitmq:
    host: localhost
    port: 55672
    username: nessyap
    password: nessyap
    publisher-confirm-type: simple
  threads:
    virtual:
      enabled: true
  mvc:
    problemdetails:
      enabled: true
erp:
  matching:
    price-tolerance-percent: 2
  outbox:
    poll-interval-ms: 500
management:
  endpoints:
    web:
      exposure:
        include: health,info
```

`erp-sim/src/main/resources/db/changelog/db.changelog-master.yaml` (Boot's default changelog location, so no property is needed):

```yaml
databaseChangeLog: []
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `./mvnw -q spotless:apply license:format && ./mvnw -q -pl :erp-sim -am test`
Expected: PASS, 2 tests.

- [ ] **Step 7: Check the Compose stack comes up**

Run: `docker compose up -d && docker compose ps`
Expected: `postgres` and `rabbitmq` both `running`. Then `docker compose exec postgres psql -U nessyap -l` lists `erp`, `apagent` and `keycloak`. Leave the stack running or stop it with `docker compose down`; nothing in the build depends on it.

- [ ] **Step 8: Full gate and commit**

Run: `./mvnw -q clean verify` (exit code 0).

```bash
git add .gitignore pom.xml mvnw mvnw.cmd .mvn compose.yaml compose CLAUDE.md README.md erp-sim
git commit -m "build: erp-sim skeleton booting against Postgres and RabbitMQ

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AFNcEjRLnMqMPkEMiki84C"
```

---
### Task 2: Event contracts, the schema, and the write-side plumbing (ids, clock, audit, outbox)

**Files:**
- Modify: `pom.xml` (add the `ap-contracts` module and manage its version), `erp-sim/pom.xml` (depend on it), `erp-sim/src/main/resources/db/changelog/db.changelog-master.yaml`
- Create: `ap-contracts/pom.xml`
- Create in `ap-contracts/src/main/java/org/jwcarman/nessyap/contracts/`: `ReasonCode.java`, `ErpEvent.java`, `MatchExceptionRaised.java`, `ReceiptPosted.java`, `InvoiceResolved.java`, `VendorBankChangeProposed.java`, `ErpEvents.java`
- Create: `erp-sim/src/main/resources/db/changelog/001-erp-schema.sql`
- Create in `erp-sim/src/main/java/org/jwcarman/nessyap/erp/`: `support/Ids.java`, `support/TimeConfig.java`, `support/ApiException.java`, `support/NotFoundException.java`, `support/InvalidRequestException.java`, `support/ErpReset.java`, `audit/Actor.java`, `audit/AuditLog.java`, `outbox/Outbox.java`
- Test: `ap-contracts/src/test/java/org/jwcarman/nessyap/contracts/ErpEventsTest.java`, `ap-contracts/src/test/resources/junit-platform.properties`, `erp-sim/src/test/java/org/jwcarman/nessyap/erp/ErpIntegrationTest.java`, `erp-sim/src/test/java/org/jwcarman/nessyap/erp/audit/AuditLogTest.java`, `erp-sim/src/test/java/org/jwcarman/nessyap/erp/outbox/OutboxTest.java`

**Interfaces:**
- Consumes: `ErpContainers` (Task 1).
- Produces:
  - `enum ReasonCode { PRICE_VARIANCE, QTY_OVER_RECEIPT, NO_RECEIPT, DUPLICATE, NO_PO, UNPLANNED_CHARGE, VENDOR_BANK_CHANGED }`
  - `sealed interface ErpEvent { UUID eventId(); Instant occurredAt(); }` with records
    `MatchExceptionRaised(UUID eventId, Instant occurredAt, UUID exceptionId, UUID invoiceId, String invoiceNumber, UUID vendorId, String poNumber, ReasonCode reasonCode, String summary, BigDecimal amountAtIssue)`,
    `ReceiptPosted(UUID eventId, Instant occurredAt, UUID receiptId, String poNumber)`,
    `InvoiceResolved(UUID eventId, Instant occurredAt, UUID invoiceId, String action, String status)`,
    `VendorBankChangeProposed(UUID eventId, Instant occurredAt, UUID vendorId, UUID bankAccountId)`
  - `ErpEvents.EXCHANGE = "erp.events"`, `ErpEvents.routingKey(ErpEvent) -> String`
  - `Ids.next() -> UUID`
  - a `Clock` bean ticking in microseconds
  - `abstract class ApiException extends RuntimeException { HttpStatus status(); String code(); }`, `NotFoundException(String kind, Object id)` (404, `NOT_FOUND`), `InvalidRequestException(String message)` (400, `INVALID_REQUEST`)
  - `record Actor(String client, String user)` with `Actor.anonymous()` and `Actor.system()`
  - `AuditLog.record(Actor actor, String entityType, UUID entityId, String action, String detail)`
  - `Outbox.append(ErpEvent event)` (must run inside a transaction)
  - `ErpReset.reset()` (truncates every table)
  - test base `ErpIntegrationTest` with protected `JdbcClient jdbc` and `long count(String table)`; resets the database before each test

- [ ] **Step 1: Add the `ap-contracts` module to the build**

In root `pom.xml`, change `<modules>` to:

```xml
  <modules>
    <module>ap-contracts</module>
    <module>erp-sim</module>
  </modules>
```

and add this entry inside `<dependencyManagement><dependencies>` after the JUG entry:

```xml
      <dependency>
        <groupId>org.jwcarman.nessyap</groupId>
        <artifactId>ap-contracts</artifactId>
        <version>${project.version}</version>
      </dependency>
```

`ap-contracts/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.jwcarman.nessyap</groupId>
    <artifactId>nessy-ap</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </parent>

  <artifactId>ap-contracts</artifactId>
  <name>Nessy AP :: contracts</name>

  <dependencies>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.assertj</groupId>
      <artifactId>assertj-core</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>
</project>
```

In `erp-sim/pom.xml`, add as the first dependency:

```xml
    <dependency>
      <groupId>org.jwcarman.nessyap</groupId>
      <artifactId>ap-contracts</artifactId>
    </dependency>
```

`ap-contracts/src/test/resources/junit-platform.properties`:

```properties
junit.jupiter.displayname.generator.default=org.junit.jupiter.api.DisplayNameGenerator$ReplaceUnderscores
```

- [ ] **Step 2: Write the failing contracts test**

`ap-contracts/src/test/java/org/jwcarman/nessyap/contracts/ErpEventsTest.java`:

```java
package org.jwcarman.nessyap.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ErpEventsTest {

  private static final UUID ID = UUID.fromString("00000000-0000-7000-8000-000000000001");

  @Test
  void a_raised_exception_routes_as_match_exception_raised() {
    ErpEvent event =
        new MatchExceptionRaised(
            ID, Instant.EPOCH, ID, ID, "INV-1", ID, "PO-1", ReasonCode.NO_PO, "s", BigDecimal.ONE);
    assertThat(ErpEvents.routingKey(event)).isEqualTo("match-exception.raised");
  }

  @Test
  void a_posted_receipt_routes_as_receipt_posted() {
    assertThat(ErpEvents.routingKey(new ReceiptPosted(ID, Instant.EPOCH, ID, "PO-1")))
        .isEqualTo("receipt.posted");
  }

  @Test
  void a_resolved_invoice_routes_as_invoice_resolved() {
    assertThat(
            ErpEvents.routingKey(new InvoiceResolved(ID, Instant.EPOCH, ID, "hold", "ON_HOLD")))
        .isEqualTo("invoice.resolved");
  }

  @Test
  void a_proposed_bank_change_routes_as_vendor_bank_change_proposed() {
    assertThat(ErpEvents.routingKey(new VendorBankChangeProposed(ID, Instant.EPOCH, ID, ID)))
        .isEqualTo("vendor.bank-change.proposed");
  }

  @Test
  void every_event_goes_to_the_one_exchange() {
    assertThat(ErpEvents.EXCHANGE).isEqualTo("erp.events");
  }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `./mvnw -q spotless:apply license:format && ./mvnw -q -pl :ap-contracts test`
Expected: FAIL to compile (`ErpEvent` and friends do not exist).

- [ ] **Step 4: Write the contracts**

`ReasonCode.java`:

```java
package org.jwcarman.nessyap.contracts;

/** Why a three-way match failed. One exception is raised per reason. */
public enum ReasonCode {
  PRICE_VARIANCE,
  QTY_OVER_RECEIPT,
  NO_RECEIPT,
  DUPLICATE,
  NO_PO,
  UNPLANNED_CHARGE,
  VENDOR_BANK_CHANGED
}
```

`ErpEvent.java`:

```java
package org.jwcarman.nessyap.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Something the ERP tells the world. Delivered at least once, so {@link #eventId()} is what a
 * consumer deduplicates on.
 */
public sealed interface ErpEvent
    permits MatchExceptionRaised, ReceiptPosted, InvoiceResolved, VendorBankChangeProposed {

  UUID eventId();

  Instant occurredAt();
}
```

`MatchExceptionRaised.java`:

```java
package org.jwcarman.nessyap.contracts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A three-way match failed for one reason.
 *
 * @param poNumber the purchase-order number as written on the invoice; null when it cites none
 * @param amountAtIssue the money the exception puts in question, two decimal places
 */
public record MatchExceptionRaised(
    UUID eventId,
    Instant occurredAt,
    UUID exceptionId,
    UUID invoiceId,
    String invoiceNumber,
    UUID vendorId,
    String poNumber,
    ReasonCode reasonCode,
    String summary,
    BigDecimal amountAtIssue)
    implements ErpEvent {}
```

`ReceiptPosted.java`:

```java
package org.jwcarman.nessyap.contracts;

import java.time.Instant;
import java.util.UUID;

/** Goods arrived against a purchase order. */
public record ReceiptPosted(UUID eventId, Instant occurredAt, UUID receiptId, String poNumber)
    implements ErpEvent {}
```

`InvoiceResolved.java`:

```java
package org.jwcarman.nessyap.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * A resolution command was applied to an invoice.
 *
 * @param action the command's slug, e.g. {@code approve-variance}
 * @param status the invoice status it left behind
 */
public record InvoiceResolved(
    UUID eventId, Instant occurredAt, UUID invoiceId, String action, String status)
    implements ErpEvent {}
```

`VendorBankChangeProposed.java`:

```java
package org.jwcarman.nessyap.contracts;

import java.time.Instant;
import java.util.UUID;

/** Someone asked to change where a vendor is paid. Unverified until two people confirm it. */
public record VendorBankChangeProposed(
    UUID eventId, Instant occurredAt, UUID vendorId, UUID bankAccountId) implements ErpEvent {}
```

`ErpEvents.java`:

```java
package org.jwcarman.nessyap.contracts;

/** Where ERP events are published and under which routing key. */
public final class ErpEvents {

  /** The durable topic exchange every ERP event is published to. */
  public static final String EXCHANGE = "erp.events";

  private ErpEvents() {}

  public static String routingKey(ErpEvent event) {
    return switch (event) {
      case MatchExceptionRaised _ -> "match-exception.raised";
      case ReceiptPosted _ -> "receipt.posted";
      case InvoiceResolved _ -> "invoice.resolved";
      case VendorBankChangeProposed _ -> "vendor.bank-change.proposed";
    };
  }
}
```

- [ ] **Step 5: Run the contracts test to verify it passes**

Run: `./mvnw -q spotless:apply license:format && ./mvnw -q -pl :ap-contracts test`
Expected: PASS, 5 tests.

- [ ] **Step 6: Write the schema**

`erp-sim/src/main/resources/db/changelog/db.changelog-master.yaml` (replace the whole file):

```yaml
databaseChangeLog:
  - include:
      file: db/changelog/001-erp-schema.sql
```

`erp-sim/src/main/resources/db/changelog/001-erp-schema.sql`:

```sql
--liquibase formatted sql

--changeset jcarman:001-erp-schema
create table vendor (
    id            uuid primary key,
    name          text        not null,
    payment_terms text        not null,
    contact_name  text        not null,
    contact_phone text        not null,
    contact_email text        not null,
    created_at    timestamptz not null
);

create table vendor_bank_account (
    id                uuid primary key,
    vendor_id         uuid        not null references vendor (id),
    account_number    text        not null,
    routing_number    text        not null,
    status            text        not null,
    proposed_at       timestamptz not null,
    proposed_by_email text
);
create index vendor_bank_account_vendor on vendor_bank_account (vendor_id);

create table purchase_order (
    id         uuid primary key,
    po_number  text        not null unique,
    vendor_id  uuid        not null references vendor (id),
    buyer      text        not null,
    created_at timestamptz not null
);

create table po_line (
    id         uuid primary key,
    po_id      uuid           not null references purchase_order (id),
    line_no    int            not null,
    item       text           not null,
    quantity   numeric(14, 3) not null,
    unit_price numeric(14, 2) not null,
    unique (po_id, line_no)
);

create table goods_receipt (
    id          uuid primary key,
    po_id       uuid        not null references purchase_order (id),
    received_at timestamptz not null
);
create index goods_receipt_po on goods_receipt (po_id);

create table receipt_line (
    id         uuid primary key,
    receipt_id uuid           not null references goods_receipt (id),
    po_line_no int            not null,
    quantity   numeric(14, 3) not null
);

create table invoice (
    id              uuid primary key,
    vendor_id       uuid           not null references vendor (id),
    invoice_number  text           not null,
    po_number       text,
    invoice_date    date           not null,
    tax             numeric(14, 2) not null,
    freight         numeric(14, 2) not null,
    total           numeric(14, 2) not null,
    approved_amount numeric(14, 2),
    status          text           not null,
    version         bigint         not null,
    received_at     timestamptz    not null
);
create index invoice_vendor on invoice (vendor_id);

create table invoice_line (
    id          uuid primary key,
    invoice_id  uuid           not null references invoice (id),
    line_no     int            not null,
    po_line_no  int,
    description text           not null,
    quantity    numeric(14, 3) not null,
    unit_price  numeric(14, 2) not null,
    unique (invoice_id, line_no)
);

create table match_exception (
    id              uuid primary key,
    invoice_id      uuid           not null references invoice (id),
    reason_code     text           not null,
    summary         text           not null,
    amount_at_issue numeric(14, 2) not null,
    status          text           not null,
    raised_at       timestamptz    not null,
    resolved_at     timestamptz
);
create index match_exception_invoice on match_exception (invoice_id);

create table erp_audit (
    id            uuid primary key,
    at            timestamptz not null,
    entity_type   text        not null,
    entity_id     uuid        not null,
    action        text        not null,
    acting_client text        not null,
    acting_user   text,
    detail        text        not null
);
create index erp_audit_entity on erp_audit (entity_id);

create table outbox (
    id           uuid primary key,
    event_type   text        not null,
    payload      jsonb       not null,
    created_at   timestamptz not null,
    published_at timestamptz
);
create index outbox_unpublished on outbox (created_at) where published_at is null;

create table idempotency_record (
    idempotency_key text primary key,
    request_hash    text        not null,
    response_status int,
    response_body   text,
    created_at      timestamptz not null
);
```

- [ ] **Step 7: Write the failing plumbing tests**

`erp-sim/src/test/java/org/jwcarman/nessyap/erp/ErpIntegrationTest.java`:

```java
package org.jwcarman.nessyap.erp;

import org.junit.jupiter.api.BeforeEach;
import org.jwcarman.nessyap.erp.support.ErpReset;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Base for every test that needs the running ERP. Each test starts from empty tables; the Spring
 * context, and with it the containers, is shared.
 */
@SpringBootTest
@Import(ErpContainers.class)
public abstract class ErpIntegrationTest {

  @Autowired protected JdbcClient jdbc;
  @Autowired private ErpReset erpReset;

  @BeforeEach
  void startFromEmptyTables() {
    erpReset.reset();
  }

  protected long count(String table) {
    return jdbc.sql("select count(*) from " + table).query(Long.class).single();
  }
}
```

`erp-sim/src/test/java/org/jwcarman/nessyap/erp/audit/AuditLogTest.java`:

```java
package org.jwcarman.nessyap.erp.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessyap.erp.ErpIntegrationTest;
import org.jwcarman.nessyap.erp.support.Ids;
import org.springframework.beans.factory.annotation.Autowired;

class AuditLogTest extends ErpIntegrationTest {

  @Autowired AuditLog auditLog;

  @Test
  void records_who_did_what_to_which_entity() {
    UUID entity = Ids.next();

    auditLog.record(new Actor("workbench", "connie"), "invoice", entity, "hold", "waiting on PO");

    Map<String, Object> row =
        jdbc.sql("select * from erp_audit where entity_id = :id").param("id", entity).query().singleRow();
    assertThat(row)
        .containsEntry("entity_type", "invoice")
        .containsEntry("action", "hold")
        .containsEntry("acting_client", "workbench")
        .containsEntry("acting_user", "connie")
        .containsEntry("detail", "waiting on PO");
    assertThat(row.get("at")).isNotNull();
  }

  @Test
  void records_an_anonymous_caller_with_no_user() {
    UUID entity = Ids.next();

    auditLog.record(Actor.anonymous(), "invoice", entity, "received", "d");

    Map<String, Object> row =
        jdbc.sql("select * from erp_audit where entity_id = :id").param("id", entity).query().singleRow();
    assertThat(row).containsEntry("acting_client", "anonymous");
    assertThat(row.get("acting_user")).isNull();
  }
}
```

`erp-sim/src/test/java/org/jwcarman/nessyap/erp/outbox/OutboxTest.java`:

```java
package org.jwcarman.nessyap.erp.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.jwcarman.nessyap.contracts.ReceiptPosted;
import org.jwcarman.nessyap.erp.ErpIntegrationTest;
import org.jwcarman.nessyap.erp.support.Ids;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

class OutboxTest extends ErpIntegrationTest {

  @Autowired Outbox outbox;
  @Autowired TransactionTemplate tx;

  @Test
  void stores_the_event_under_its_routing_key_as_json() {
    UUID eventId = Ids.next();
    ReceiptPosted event = new ReceiptPosted(eventId, Instant.parse("2026-10-02T12:00:00Z"), Ids.next(), "PO-7");

    tx.executeWithoutResult(status -> outbox.append(event));

    Map<String, Object> row =
        jdbc.sql("select event_type, payload::text as payload, published_at from outbox where id = :id")
            .param("id", eventId)
            .query()
            .singleRow();
    assertThat(row).containsEntry("event_type", "receipt.posted");
    assertThat((String) row.get("payload"))
        .contains("\"eventId\": \"" + eventId + "\"")
        .contains("\"poNumber\": \"PO-7\"");
    assertThat(row.get("published_at")).isNull();
  }

  @Test
  void refuses_to_append_outside_a_transaction() {
    ReceiptPosted event = new ReceiptPosted(Ids.next(), Instant.EPOCH, Ids.next(), "PO-7");

    assertThatThrownBy(() -> outbox.append(event))
        .isInstanceOf(IllegalTransactionStateException.class);
  }
}
```

Note: Postgres renders `jsonb::text` with a space after each colon, which is why the assertions expect `"eventId": "`.

- [ ] **Step 8: Run them to verify they fail**

Run: `./mvnw -q spotless:apply license:format && ./mvnw -q -pl :erp-sim -am test -Dtest='AuditLogTest,OutboxTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL to compile (`ErpReset`, `AuditLog`, `Actor`, `Outbox`, `Ids` do not exist).

- [ ] **Step 9: Write the support classes**

`support/Ids.java`:

```java
package org.jwcarman.nessyap.erp.support;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import java.util.UUID;

/** Every id the ERP mints: time-ordered UUIDv7, so rows sort by when they were made. */
public final class Ids {

  private static final TimeBasedEpochGenerator GENERATOR = Generators.timeBasedEpochGenerator();

  private Ids() {}

  public static UUID next() {
    return GENERATOR.generate();
  }
}
```

`support/TimeConfig.java`:

```java
package org.jwcarman.nessyap.erp.support;

import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one clock. It ticks in microseconds because that is all a Postgres {@code timestamptz}
 * keeps; an instant with nanoseconds would not survive a round trip unchanged.
 */
@Configuration(proxyBeanMethods = false)
public class TimeConfig {

  @Bean
  public Clock clock() {
    return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
  }
}
```

`support/ApiException.java`:

```java
package org.jwcarman.nessyap.erp.support;

import org.springframework.http.HttpStatus;

/** A failure the API reports as a problem response with this status and a stable code. */
public abstract class ApiException extends RuntimeException {

  private final HttpStatus status;
  private final String code;

  protected ApiException(HttpStatus status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public HttpStatus status() {
    return status;
  }

  public String code() {
    return code;
  }
}
```

`support/NotFoundException.java`:

```java
package org.jwcarman.nessyap.erp.support;

import org.springframework.http.HttpStatus;

public class NotFoundException extends ApiException {

  public NotFoundException(String kind, Object id) {
    super(HttpStatus.NOT_FOUND, "NOT_FOUND", "No " + kind + " " + id);
  }
}
```

`support/InvalidRequestException.java`:

```java
package org.jwcarman.nessyap.erp.support;

import org.springframework.http.HttpStatus;

public class InvalidRequestException extends ApiException {

  public InvalidRequestException(String message) {
    super(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
  }
}
```

`support/ErpReset.java`:

```java
package org.jwcarman.nessyap.erp.support;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Empties every table. Used by tests and by the admin reset endpoint. */
@Component
public class ErpReset {

  private final JdbcClient jdbc;

  public ErpReset(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public void reset() {
    jdbc.sql(
            """
            truncate table idempotency_record, outbox, erp_audit, match_exception, invoice_line,
                invoice, receipt_line, goods_receipt, po_line, purchase_order,
                vendor_bank_account, vendor
            """)
        .update();
  }
}
```

`audit/Actor.java`:

```java
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
```

`audit/AuditLog.java`:

```java
package org.jwcarman.nessyap.erp.audit;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.util.UUID;
import org.jwcarman.nessyap.erp.support.Ids;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The ERP's own record of every change: what, to which entity, by whom. */
@Component
public class AuditLog {

  private final JdbcClient jdbc;
  private final Clock clock;

  public AuditLog(JdbcClient jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  public void record(Actor actor, String entityType, UUID entityId, String action, String detail) {
    jdbc.sql(
            """
            insert into erp_audit
                (id, at, entity_type, entity_id, action, acting_client, acting_user, detail)
            values (:id, :at, :entityType, :entityId, :action, :client, :user, :detail)
            """)
        .param("id", Ids.next())
        .param("at", Timestamp.from(clock.instant()))
        .param("entityType", entityType)
        .param("entityId", entityId)
        .param("action", action)
        .param("client", actor.client())
        .param("user", actor.user(), Types.VARCHAR)
        .param("detail", detail)
        .update();
  }
}
```

`outbox/Outbox.java`:

```java
package org.jwcarman.nessyap.erp.outbox;

import java.sql.Timestamp;
import org.jwcarman.nessyap.contracts.ErpEvent;
import org.jwcarman.nessyap.contracts.ErpEvents;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Where an event is written down, in the same transaction as the change it describes, so the two
 * commit or vanish together. {@link OutboxPublisher} delivers it later.
 */
@Component
public class Outbox {

  private final JdbcClient jdbc;
  private final JsonMapper json;

  public Outbox(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void append(ErpEvent event) {
    jdbc.sql(
            """
            insert into outbox (id, event_type, payload, created_at)
            values (:id, :type, cast(:payload as jsonb), :createdAt)
            """)
        .param("id", event.eventId())
        .param("type", ErpEvents.routingKey(event))
        .param("payload", json.writeValueAsString(event))
        .param("createdAt", Timestamp.from(event.occurredAt()))
        .update();
  }
}
```

The `{@link OutboxPublisher}` above refers to the class Task 6 creates. Until then write `{@code OutboxPublisher}` instead, and Task 6 changes it to `{@link}`.

- [ ] **Step 10: Run the tests to verify they pass**

Run: `./mvnw -q spotless:apply license:format && ./mvnw -q -pl :erp-sim -am test`
Expected: PASS: `ErpSimApplicationTest` (2), `AuditLogTest` (2), `OutboxTest` (2). If Boot does not provide a `JsonMapper` bean, the context fails with "No qualifying bean of type 'tools.jackson.databind.json.JsonMapper'": then add `@Bean public JsonMapper jsonMapper() { return JsonMapper.builder().findAndAddModules().build(); }` to `TimeConfig` (renaming nothing) and rerun.

- [ ] **Step 11: Full gate and commit**

Run: `./mvnw -q clean verify` (exit code 0).

```bash
git add pom.xml ap-contracts erp-sim
git commit -m "feat: event contracts, ERP schema, audit log and outbox

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01AFNcEjRLnMqMPkEMiki84C"
```

---

> **From Task 3 on, tasks give signatures, rules, test cases and traps rather than full code.**
> Follow the patterns Tasks 1 and 2 established: `JdbcClient` with text-block SQL, `Timestamp.from(instant)` for writes, `rs.getTimestamp(..).toInstant()` / `rs.getObject(.., UUID.class)` / `rs.getObject(.., LocalDate.class)` for reads, `.param(name, value, Types.X)` for any value that may be null, services `@Transactional` (reads `readOnly = true`), every write takes an `Actor` and records an `AuditLog` row, tests extend `ErpIntegrationTest`.

### Task 3: Master data: vendors, purchase orders, goods receipts

**Files:** `erp-sim/src/main/java/org/jwcarman/nessyap/erp/vendor/*`, `erp-sim/src/main/java/org/jwcarman/nessyap/erp/po/*`; tests `vendor/VendorMasterTest`, `po/PurchaseOrdersTest`, `po/GoodsReceiptsTest`.

**Interfaces (produces):**
- `vendor`: `Contact(String name, String phone, String email)`; `enum BankAccountStatus { ACTIVE, PENDING_VERIFICATION, SUPERSEDED, REJECTED }`; `BankAccount(UUID id, String accountNumber, String routingNumber, BankAccountStatus status, Instant proposedAt, String proposedByEmail)`; `Vendor(UUID id, String name, String paymentTerms, Contact contact, Instant createdAt, List<BankAccount> bankAccounts)` with `boolean hasUnverifiedBankChange()`; `NewVendor(String name, String paymentTerms, Contact contact, String accountNumber, String routingNumber)`; `BankChangeProposal(String accountNumber, String routingNumber, String proposedByEmail)`.
- `VendorMaster`: `Vendor create(Actor, NewVendor)`, `Vendor get(UUID)` (throws `NotFoundException("vendor", id)`), `BankAccount proposeBankChange(Actor, UUID vendorId, BankChangeProposal)`.
- `po`: `PoLine(int lineNo, String item, BigDecimal quantity, BigDecimal unitPrice)`; `PurchaseOrder(UUID id, String poNumber, UUID vendorId, String buyer, Instant createdAt, List<PoLine> lines)` with `Optional<PoLine> line(int lineNo)`; `NewPurchaseOrder(String poNumber, UUID vendorId, String buyer, List<PoLine> lines)`; `ReceiptLine(int poLineNo, BigDecimal quantity)`; `GoodsReceipt(UUID id, UUID poId, Instant receivedAt, List<ReceiptLine> lines)`; `NewReceipt(String poNumber, List<ReceiptLine> lines)`.
- `PurchaseOrderRepository.findByNumber(String) -> Optional<PurchaseOrder>`, `ReceiptRepository.findByPo(UUID poId) -> List<GoodsReceipt>` (Task 5 uses both).
- `PurchaseOrders`: `PurchaseOrder create(Actor, NewPurchaseOrder)`, `PurchaseOrder get(String poNumber)`, `List<GoodsReceipt> receipts(String poNumber)`.
- `GoodsReceipts`: `GoodsReceipt post(Actor, NewReceipt)`.

**Rules:**
- Records copy their lists (`List.copyOf`) in a compact constructor.
- `create` writes the vendor plus one `ACTIVE` bank account (`proposedAt` = now, `proposedByEmail` null).
- `proposeBankChange` writes a `PENDING_VERIFICATION` account, audits `bank-change-proposed`, appends `VendorBankChangeProposed`. Accounts load ordered by `proposed_at`.
- `PurchaseOrders.create` rejects with `InvalidRequestException`: blank PO number, a PO number already in use, no lines, duplicate line numbers, non-positive quantity or price. An unknown vendor gives `NotFoundException`.
- `GoodsReceipts.post`: unknown PO gives `NotFoundException("purchase order", number)`. No lines, a line naming a PO line number the PO lacks, or a non-positive quantity give `InvalidRequestException`. A good receipt audits `received` and appends `ReceiptPosted`.
- Child rows (accounts, PO lines, receipt lines) are inserted one statement per row and read back with one query per parent. The volumes are tiny.

**Tests (all integration):**
- `VendorMasterTest`: create returns one ACTIVE account and `hasUnverifiedBankChange()` false; get of a random id throws `NotFoundException`; proposeBankChange makes `hasUnverifiedBankChange()` true, writes one outbox row `vendor.bank-change.proposed` and one audit row with action `bank-change-proposed`.
- `PurchaseOrdersTest`: create then get by number round-trips lines (compare quantities with `isEqualByComparingTo`); duplicate number is rejected; unknown vendor gives `NotFoundException`.
- `GoodsReceiptsTest`: post writes outbox `receipt.posted`; a line for PO line 9 on a one-line PO is rejected; `receipts(poNumber)` returns what was posted.

**Commit:** `feat: vendors, purchase orders and goods receipts`

---

### Task 4: The matching engine (pure, no Spring)

**Files:** `erp-sim/src/main/java/org/jwcarman/nessyap/erp/matching/` `Tolerances`, `MatchLine`, `PriorInvoice`, `MatchInput`, `MatchFinding`, `InvoiceNumbers`, `MatchEngine`; test `matching/MatchEngineTest` (plain JUnit, no Spring context).

**Interfaces (produces):**
- `Tolerances(BigDecimal pricePercent, int duplicateWindowDays)` with `static Tolerances defaults()` = (2, 7).
- `MatchLine(int lineNo, Integer poLineNo, BigDecimal quantity, BigDecimal unitPrice)`. A null `poLineNo` means the line cites no PO line.
- `PriorInvoice(UUID id, String invoiceNumber, String poNumber, LocalDate invoiceDate, BigDecimal total)`.
- `MatchInput(UUID invoiceId, String invoiceNumber, LocalDate invoiceDate, UUID vendorId, String poNumber, BigDecimal freight, BigDecimal total, List<MatchLine> lines, PurchaseOrder purchaseOrder, Map<Integer, BigDecimal> receivedByPoLine, boolean vendorHasUnverifiedBankChange, List<PriorInvoice> priorInvoices)`. `purchaseOrder` is null when no PO with that number exists. `receivedByPoLine` is total received per PO line number.
- `MatchFinding(ReasonCode code, String summary, BigDecimal amountAtIssue)`.
- `InvoiceNumbers.normalize(String) -> String`: strip everything but letters and digits, upper-case with `Locale.ROOT`.
- `MatchEngine(Tolerances)` with `List<MatchFinding> match(MatchInput)`.

**Rules, in this order (at most one finding per reason code):**
1. **DUPLICATE** if any prior invoice (skipping one whose id equals `invoiceId`) has an equal normalized number, OR the same non-null `poNumber`, an equal total (`compareTo`) and an invoice date within `duplicateWindowDays`. Amount = invoice total.
2. **VENDOR_BANK_CHANGED** if `vendorHasUnverifiedBankChange`. Amount = total.
3. **NO_PO** if `purchaseOrder` is null ("Invoice cites no purchase order" or "No purchase order X exists") or belongs to another vendor ("Purchase order X belongs to a different vendor"). Amount = total. **Stop here**: no PO checks follow.
4. **PRICE_VARIANCE**: for lines whose PO line exists, flag when `unitPrice > poPrice × (1 + pct/100)` (strictly greater). Amount = Σ (unitPrice − poPrice) × quantity. Each line's note reads `Line N billed 10.40 against PO price 10.00 (+4.00%)`, with the percent at 2 dp HALF_UP. Notes are joined with `"; "`.
5. **Receipts**, over lines whose PO line exists:
   - If total received across them is zero, the finding is **NO_RECEIPT**, with amount Σ quantity × unitPrice.
   - Otherwise it is **QTY_OVER_RECEIPT** for lines where billed quantity > received quantity. Amount = Σ (billed − received) × unitPrice. Notes read `Line N billed 100 but 60 received`, with quantities as `stripTrailingZeros().toPlainString()`.
6. **UNPLANNED_CHARGE**: freight > 0, plus every line whose `poLineNo` is null **or names a PO line the PO lacks** (Review Focus 1). Amount = freight + Σ quantity × unitPrice of those lines.

Every amount is `setScale(2, HALF_UP)`. The result is an unmodifiable list.

**Tests (`@Nested` per reason; build inputs with small static helpers; assert `extracting(MatchFinding::code)` and `amountAtIssue` with `isEqualByComparingTo`).** The base case is PO line 1 = 100 @ 10.00, received 100, invoice line 1 = 100 @ 10.00, no freight, no priors.
- `When_everything_agrees`: no findings.
- `When_the_price_is_over_tolerance`:
  - 10.40 gives PRICE_VARIANCE 40.00, with a summary containing `+4.00%`
  - 10.20 (exactly 2%) gives nothing
  - 10.21 gives PRICE_VARIANCE 21.00
  - 9.50 gives nothing
- `When_receipts_fall_short`:
  - received 60 gives QTY_OVER_RECEIPT 400.00 only, with a summary containing `billed 100 but 60 received`
  - an empty received map gives NO_RECEIPT 1000.00 and no QTY_OVER_RECEIPT
- `When_there_is_no_purchase_order`:
  - a null PO plus freight 50 gives exactly [NO_PO] with 1050.00 (the total includes freight)
  - a PO whose vendor differs gives [NO_PO]
- `When_charges_are_not_on_the_po`:
  - freight 85.00 gives UNPLANNED_CHARGE 85.00
  - a line citing PO line 9 gives UNPLANNED_CHARGE equal to its extended value, and no NO_RECEIPT or exception is raised
- `When_the_invoice_repeats_an_earlier_one`:
  - prior `inv1001` against `INV-1001` gives DUPLICATE
  - the same PO and total 5 days apart gives DUPLICATE
  - the same PO and total 30 days apart gives nothing
  - a prior carrying the invoice's own id gives nothing
- `When_the_vendors_bank_details_changed`: otherwise-clean input gives exactly [VENDOR_BANK_CHANGED].

**Commit:** `feat: three-way matching engine`

---

### Task 5: Invoice intake: persist, match, raise exceptions

**Files:** `invoice/` `InvoiceStatus`, `InvoiceLine`, `Invoice`, `NewInvoice`, `InvoiceRepository`, `InvoiceIntake`; `matching/` `ExceptionStatus`, `MatchException`, `MatchExceptionRepository`, `MatchingConfig`; tests `TestData` (test root package), `invoice/InvoiceIntakeTest`. Add `protected TestData data()` to `ErpIntegrationTest` (build it from the autowired services).

**Interfaces (produces):**
- `enum InvoiceStatus { RECEIVED, MATCHED, EXCEPTION, APPROVED, ON_HOLD, REJECTED, PAID }`.
- `InvoiceLine(int lineNo, Integer poLineNo, String description, BigDecimal quantity, BigDecimal unitPrice)`.
- `Invoice(UUID id, UUID vendorId, String invoiceNumber, String poNumber, LocalDate invoiceDate, BigDecimal tax, BigDecimal freight, BigDecimal total, BigDecimal approvedAmount, InvoiceStatus status, long version, Instant receivedAt, List<InvoiceLine> lines)` with `Invoice withLines(List<InvoiceLine>)`.
- `NewInvoice(UUID vendorId, String invoiceNumber, String poNumber, LocalDate invoiceDate, BigDecimal tax, BigDecimal freight, List<InvoiceLine> lines)`.
- `InvoiceRepository`: `insert(Invoice)`, `Optional<Invoice> find(UUID)`, `List<Invoice> findByVendor(UUID)` (newest first), `List<PriorInvoice> priorInvoices(UUID vendorId, UUID excluding)` (status ≠ REJECTED), `boolean updateStatus(UUID id, InvoiceStatus status, BigDecimal approvedAmount, long expectedVersion)`, which bumps `version` and returns false when no row had that version.
- `enum ExceptionStatus { OPEN, RESOLVED }`; `MatchException(UUID id, UUID invoiceId, ReasonCode reasonCode, String summary, BigDecimal amountAtIssue, ExceptionStatus status, Instant raisedAt, Instant resolvedAt)`.
- `MatchExceptionRepository`: `insert`, `Optional<MatchException> find(UUID)`, `List<MatchException> findByInvoice(UUID)`, `List<MatchException> findByStatus(ExceptionStatus)`, `int resolveOpenForInvoice(UUID invoiceId, Instant at)`.
- `MatchingConfig`: `@Bean MatchEngine` from `erp.matching.price-tolerance-percent` (duplicate window 7).
- `InvoiceIntake.receive(Actor, NewInvoice) -> Invoice`.
- `TestData` (test helper, plain class):
  - `Vendor vendor()` creates "Acme Fasteners", NET30
  - `PurchaseOrder po(Vendor, String poNumber)` creates line 1 = 100 × "M8 bolts" @ 10.00, buyer `bob`
  - `GoodsReceipt receive(String poNumber, String quantity)` receives against line 1
  - `Invoice invoice(Vendor, String number, String poNumber, String quantity, String unitPrice)` bills one line on PO line 1, dated 2026-10-01, with no tax or freight

**Rules for `receive`, all in one transaction:**
- Validate vendor (404), at least one line, positive quantities and prices, and unique line numbers (400).
- Total = Σ quantity × unitPrice + tax + freight, scale 2 HALF_UP.
- Insert with RECEIVED, version 0, and audit `received`.
- Resolve the PO by number (null if absent), sum receipts per PO line, load priors, and run the engine.
- Set MATCHED or EXCEPTION via `updateStatus(.., 0)`.
- For each finding: insert an OPEN `MatchException`, audit `raised` on entity type `match_exception`, and append `MatchExceptionRaised`.
- Return the re-read invoice.

**Tests (`InvoiceIntakeTest`):**
- A clean invoice is MATCHED at version 1, with no exceptions and no `match-exception.raised` outbox rows.
- 10.40 against 10.00 is EXCEPTION, with one OPEN PRICE_VARIANCE and one outbox row whose payload contains `"reasonCode": "PRICE_VARIANCE"`.
- An unknown PO number gives NO_PO.
- A second invoice `INV 1001` after `INV-1001` gives DUPLICATE on the second.
- The total includes tax and freight (2 × 10.00 + 1.60 tax + 5.00 freight = 26.60).
- An unknown vendor gives `NotFoundException`.

**Commit:** `feat: invoice intake runs the three-way match`

---
### Task 6: Outbox publisher to RabbitMQ

**Files:** `outbox/RabbitConfig`, `outbox/OutboxPublisher`; change the `{@code OutboxPublisher}` in `Outbox`'s javadoc to `{@link OutboxPublisher}`; modify `ErpContainers` (add a tap queue); tests `outbox/OutboxPublisherTest`, `outbox/OutboxPublisherBrokerDownTest`.

**Interfaces (produces):**
- `RabbitConfig`: `@Bean TopicExchange erpEvents()`, durable, named `ErpEvents.EXCHANGE`.
- `OutboxPublisher(JdbcClient, RabbitTemplate, TransactionTemplate, Clock)` with `@Scheduled(fixedDelayString = "${erp.outbox.poll-interval-ms:500}") public void publishPending()`. The class is `@ConditionalOnProperty(name = "erp.outbox.publishing.enabled", havingValue = "true", matchIfMissing = true)`.
- `ErpContainers.TAP_QUEUE = "test.erp-events.tap"`, a non-durable queue bound to `ErpEvents.EXCHANGE` with `#`.

**Rules:**
- `publishPending` loops batches of 50 until a batch comes back short.
- Each batch is one transaction:
  - `select id, event_type, payload::text ... where published_at is null order by created_at, id limit 50 for update skip locked`
  - then `rabbit.invoke(ops -> { send each; ops.waitForConfirmsOrDie(5_000); return null; })`
  - then `update outbox set published_at = :now where id in (:ids)`.
- Each message is built with `MessageBuilder.withBody(payload UTF-8)`, content type `application/json`, `messageId` = outbox id, `type` = event type, persistent delivery. The routing key is the event type.
- Any `AmqpException` is caught in `publishPending`, logged at WARN with the message, and swallowed. The transaction rolls back, so the rows stay unpublished and the next tick retries (Review Focus 3).
- Confirms need `spring.rabbitmq.publisher-confirm-type: simple`, which is already in `application.yaml`.

**Tests:**
- `OutboxPublisherTest`:
  - Purge `TAP_QUEUE` in `@BeforeEach` via `AmqpAdmin.purgeQueue`.
  - Propose a bank change (which writes an outbox row), read its id from `outbox`, and receive from `TAP_QUEUE` with Awaitility (≤10 s), skipping messages with another `messageId`.
  - Assert the routing key is `vendor.bank-change.proposed`, the content type is JSON, and the body contains the vendor id.
  - Then await `published_at` being non-null.
- `OutboxPublisherBrokerDownTest`:
  - Runs with `@TestPropertySource(properties = "erp.outbox.publishing.enabled=false")`, which gives a separate context, so the scheduled publisher cannot race the test.
  - Build `new OutboxPublisher(jdbc, new RabbitTemplate(new CachingConnectionFactory("localhost", 1)), tx, clock)`.
  - Write an outbox row (propose a bank change) and call `publishPending()`.
  - It returns normally and the row's `published_at` is still null.

**Commit:** `feat: outbox publisher delivers ERP events to RabbitMQ`

---

### Task 7: REST API for intake and reads, with problem responses

**Files:** `web/ApiExceptionHandler`; `vendor/VendorController`; `po/PurchaseOrderController`; `invoice/InvoiceView`, `invoice/InvoiceQueries`, `invoice/InvoiceController`; `matching/MatchExceptionController`. Add `protected MockMvc mvc()` to `ErpIntegrationTest` (`MockMvcBuilders.webAppContextSetup(autowired WebApplicationContext).build()`). Tests: `web/ErpApiTest` with `@Nested` groups per controller.

**Endpoints:**

| Method and path | Body / params | Returns |
|---|---|---|
| `POST /api/vendors` | `NewVendor` | 201 `Vendor` |
| `GET /api/vendors/{id}` | | `Vendor` |
| `POST /api/vendors/{id}/bank-changes` | `BankChangeProposal` | 201 `BankAccount` |
| `GET /api/vendors/{id}/invoices` | | `List<Invoice>` |
| `POST /api/purchase-orders` | `NewPurchaseOrder` | 201 `PurchaseOrder` |
| `GET /api/purchase-orders/{poNumber}` | | `PurchaseOrder` |
| `GET /api/purchase-orders/{poNumber}/receipts` | | `List<GoodsReceipt>` |
| `POST /api/receipts` | `NewReceipt` | 201 `GoodsReceipt` |
| `POST /api/invoices` | `NewInvoice` | 201 `InvoiceView` |
| `GET /api/invoices/{id}` | | `InvoiceView` |
| `GET /api/invoices/similar` | `vendorId`, `invoiceNumber`, optional `total` | `List<Invoice>` |
| `GET /api/match-exceptions/{id}` | | `MatchException` |
| `GET /api/match-exceptions` | `status` (default `OPEN`) | `List<MatchException>` |

**Interfaces:**
- `InvoiceView(Invoice invoice, List<MatchException> exceptions)`.
- `InvoiceQueries` (read-only): `get(UUID)`, `byVendor(UUID)`, and `similar(UUID vendorId, String invoiceNumber, BigDecimal total)`, which returns vendor invoices whose normalized number equals the given one, or whose total equals `total` when it is given. Also `exception(UUID)` and `exceptions(ExceptionStatus)`.
- All writes use `Actor.anonymous()` in this slice.
- `ApiExceptionHandler` (`@RestControllerAdvice`): one handler for `ApiException` returns `ProblemDetail.forStatusAndDetail(e.status(), e.getMessage())` with property `code` = `e.code()`.

**Tests (`ErpApiTest`, JSON via `jsonPath`):**
- Vendors:
  - a create then a GET round-trip
  - a GET of a random id gives 404 with `$.code` = `NOT_FOUND`
- Purchase orders and receipts:
  - a receipt for PO line 9 gives 400 with `$.code` = `INVALID_REQUEST`
- Invoices:
  - posting 100 @ 10.40 against a received 10.00 PO gives 201, with `$.invoice.status` = `EXCEPTION` and `$.exceptions[0].reasonCode` = `PRICE_VARIANCE`
  - `similar` finds `INV-1001` when asked for `inv 1001`
- Match exceptions:
  - the list defaults to OPEN and contains the raised one

**Commit:** `feat: REST API for intake and reads`

---

### Task 8: Resolution commands with idempotency keys and versions

**Files:** `resolution/` `ResolutionAction`, `ResolutionCommand`, `StaleVersionException`, `InvalidTransitionException`, `BankChangeUnverifiedException`, `IdempotencyKeyReusedException`, `Idempotency`, `Resolutions`, `ResolutionController`; `support/Fingerprints`; tests `resolution/ResolutionsTest`, `resolution/ResolutionApiTest`.

**Interfaces (produces):**
- `ResolutionAction` has a slug, a target status, a "blocked by unverified bank change" flag, and allowed-from statuses. `static Optional<ResolutionAction> fromSlug(String)`.

  | Action | Slug | From | To | Blocked by unverified bank change |
  |---|---|---|---|---|
  | APPROVE_VARIANCE | approve-variance | EXCEPTION, ON_HOLD | APPROVED | yes |
  | SHORT_PAY | short-pay | EXCEPTION, ON_HOLD | APPROVED | yes |
  | HOLD | hold | EXCEPTION | ON_HOLD | no |
  | RELEASE_HOLD | release-hold | ON_HOLD | EXCEPTION | yes |
  | REJECT | reject | EXCEPTION, ON_HOLD | REJECTED | no |
  | REQUEST_CREDIT_MEMO | request-credit-memo | EXCEPTION, ON_HOLD | ON_HOLD | no |

- `ResolutionCommand(Long expectedVersion, BigDecimal amount, String comment)`. `expectedVersion` is boxed so that "missing" is distinguishable (400 when null). `amount` is for short-pay only.
- Exceptions:
  - `StaleVersionException`: 409 `STALE_VERSION`
  - `InvalidTransitionException`: 422 `INVALID_TRANSITION`
  - `BankChangeUnverifiedException`: 422 `BANK_CHANGE_UNVERIFIED`
  - `IdempotencyKeyReusedException`: 422 `IDEMPOTENCY_KEY_REUSED`
- `Fingerprints.sha256(String) -> String` (lower-case hex).
- `Idempotency.execute(String key, String fingerprint, Class<T> type, Supplier<T> work) -> T`, with `@Transactional(propagation = MANDATORY)`.
- `Resolutions.apply(Actor, String idempotencyKey, UUID invoiceId, ResolutionAction, ResolutionCommand) -> Invoice`.
- `POST /api/invoices/{invoiceId}/{action}` with header `Idempotency-Key` (required; blank gives 400) and a `ResolutionCommand` body. It returns the `Invoice`. An unknown slug gives 404.

**Rules:**
- `Idempotency`:
  - First, `insert ... on conflict (idempotency_key) do nothing`.
  - If it inserted: run `work`, store `response_status` 200 and `response_body` (JSON of the result), and return the result.
  - If it did not insert: read the row. A different fingerprint throws `IdempotencyKeyReusedException`; otherwise deserialize and return the stored body.
  - Postgres makes a concurrent insert of the same key wait for the first transaction, which is what makes the concurrent case apply once (Review Focus 2).
  - A failing command rolls back its key row too, so failures are not cached and a retry re-runs.
- `apply`:
  - fingerprint = sha256(`action.name() + "|" + invoiceId + "|" + json(command)`); everything else runs inside `Idempotency.execute`.
  - Load the invoice (404). Check the version, then the transition. Block money-moving actions (and release-hold) when the vendor `hasUnverifiedBankChange()` (Review Focus 5).
  - Compute the approved amount:
    - approve-variance: the total
    - short-pay: requires 0 < amount < total, else 400
    - all other actions: unchanged
  - Run `updateStatus` with the expected version; false means `StaleVersionException`.
  - On APPROVED or REJECTED, `resolveOpenForInvoice`.
  - Audit the action's slug with the comment as detail, and append `InvoiceResolved(slug, newStatus.name())`.

**Tests:**
- `ResolutionsTest` (service level):
  - approve-variance gives APPROVED, approvedAmount = total, exceptions RESOLVED, and an outbox `invoice.resolved` row
  - hold then release-hold returns to EXCEPTION
  - short-pay 950.00 on a 1,040.00 invoice gives APPROVED 950.00
  - short-pay ≥ total gives `InvalidRequestException`
  - hold on a MATCHED invoice gives `InvalidTransitionException`
  - the wrong version gives `StaleVersionException`
  - for a vendor with a proposed bank change, approve-variance and release-hold both give `BankChangeUnverifiedException`
  - the same key and command twice returns equal invoices, bumps the version once, and writes one audit row
  - the same key with a different command gives `IdempotencyKeyReusedException`
  - **concurrent**: two threads behind a `CountDownLatch` apply the same key and command, both results are equal, and there is exactly one `hold` audit row
- `ResolutionApiTest`:
  - a missing `Idempotency-Key` gives 400
  - a stale version gives 409 `$.code` = `STALE_VERSION`
  - an unknown action slug gives 404
  - a successful hold gives 200 with `$.status` = `ON_HOLD`

**Commit:** `feat: resolution commands with idempotency keys and optimistic versions`

---

### Task 9: Seed scenarios and admin endpoints

**Files:** `admin/ScenarioResult`, `admin/ScenarioCatalog`, `admin/AdminController`; test `admin/ScenarioCatalogTest`.

**Interfaces (produces):**
- `ScenarioResult(String scenario, UUID vendorId, String poNumber, UUID invoiceId, List<UUID> exceptionIds)`.
- `ScenarioCatalog`: `Set<String> names()` (insertion order) and `@Transactional ScenarioResult load(String name)` (unknown name gives `NotFoundException("scenario", name)`).
- `GET /admin/scenarios` returns the names. `POST /admin/scenarios/{name}` gives 201 `ScenarioResult`. `POST /admin/reset` gives 204 and calls `ErpReset`.

**Rules:**
- Every load is self-contained and repeatable without a reset. PO and invoice numbers get a unique suffix: the last 8 hex characters of an `Ids.next()`, upper-cased, e.g. `PO-3F9A12BC`. Each load creates its own vendor "Acme Fasteners" (contact Ada Acme, `ar@acme-fasteners.example`), and all writes use `Actor.system()`. The buyer is `bob`.
- Unless a scenario says otherwise, the base PO is 100 × "M8 hex bolts" @ 10.00, received in full, and the invoice bills 100 @ 10.00.

| Name | Variation | Expected reason codes |
|---|---|---|
| `clean-match` | none | none (MATCHED) |
| `price-variance-small` | billed @ 10.40 | PRICE_VARIANCE (40.00) |
| `price-variance-large` | PO 40 @ 250.00, received 40, billed 40 @ 290.00 | PRICE_VARIANCE (1,600.00) |
| `qty-over-receipt` | only 60 received | QTY_OVER_RECEIPT (400.00) |
| `no-receipt` | nothing received | NO_RECEIPT (1,000.00) |
| `duplicate` | a clean first invoice `INV-X`, then a second `INV X` for the same amount; the result is the second | DUPLICATE |
| `no-po` | the invoice cites a PO number that does not exist | NO_PO |
| `unplanned-freight` | freight 85.00 | UNPLANNED_CHARGE (85.00) |
| `bank-change-fraud` | before invoicing, a bank change is proposed from `accounts@acme-fasteners-billing.example` | VENDOR_BANK_CHANGED |

**Tests:**
- One `@ParameterizedTest` over the table (`@MethodSource`), asserting the invoice's exception reason codes exactly. The `clean-match` row asserts MATCHED and no exceptions.
- Loading the same scenario twice succeeds.
- An unknown name over HTTP gives 404.
- `POST /admin/reset` leaves `count("invoice") == 0`.

**Commit:** `feat: seed scenarios and admin endpoints`

---

### Task 10: Fault injection, README, final gate

**Files:** `admin/FaultRule`, `admin/FaultRules`, `admin/FaultInjectionFilter`, `admin/FaultController`; modify `README.md`; test `admin/FaultInjectionTest`.

**Interfaces (produces):**
- `FaultRule(String pathPattern, long latencyMillis, double errorRate)`. The compact constructor throws `InvalidRequestException` unless the pattern starts with `/api/`, latency ≥ 0, and 0 ≤ errorRate ≤ 1.
- `FaultRules` (in memory, `ConcurrentHashMap` keyed by pattern): `put(FaultRule)`, `clear()`, `List<FaultRule> list()`, `Optional<FaultRule> matching(String path)`. Matching uses `PathPatternParser.defaultInstance.parse(pattern).matches(PathContainer.parsePath(path))`.
- `FaultInjectionFilter extends OncePerRequestFilter`:
  - `shouldNotFilter` is true for any URI not starting with `/api/` (Review Focus 4).
  - It sleeps `latencyMillis` (on interrupt, re-interrupt and carry on).
  - With probability `errorRate` (`ThreadLocalRandom`) it answers 503 with content type `application/problem+json` and body `{"status":503,"title":"Service Unavailable","detail":"Injected fault","code":"INJECTED_FAULT"}`. Otherwise it continues the chain.
- `PUT /admin/faults` (body `FaultRule`) gives 204. `GET /admin/faults` returns the rules. `DELETE /admin/faults` gives 204.

**Tests (`FaultInjectionTest`):**
- Build MockMvc with `.addFilters(filter)`, because a hand-built MockMvc does not pick up servlet filters on its own. Clear rules in `@BeforeEach`.
- errorRate 1 on `/api/vendors/**` gives 503 with `$.code` = `INJECTED_FAULT` on a vendor GET.
- The same rule leaves `/api/purchase-orders/...` untouched.
- `GET /admin/faults` still answers 200 while a rule for `/api/**` with errorRate 1 is active.
- Latency 300 means the request takes ≥ 300 ms.
- A rule with errorRate 2 or pattern `/admin/**` gives 400.
- After `DELETE /admin/faults`, the vendor GET answers 200 again.

**README:** add an "Admin" section listing the scenario names, `POST /admin/scenarios/{name}`, `POST /admin/reset`, and a `curl` example of `PUT /admin/faults`.

**Final gate:** `./mvnw -q spotless:apply license:format && ./mvnw -q clean verify` (exit 0). Then boot it once for real:
- `docker compose up -d` and `./mvnw -pl :erp-sim spring-boot:run`
- `curl -X POST localhost:8081/admin/scenarios/price-variance-small` returns ids
- the RabbitMQ UI (http://localhost:55673, nessyap/nessyap) shows the `erp.events` exchange

Stop the app afterwards.

**Commit:** `feat: fault injection and admin docs`

# Slice 7: The Desk's Inbox as a Camel Route — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Replace the hand-rolled IMAP poller (`InboxPoller` and `InboxPolling`, about 330 lines) with an idiomatic Apache Camel route, keeping every behaviour the slice 5 tests pin down.

**Architecture:** One `RouteBuilder` bean, `DeskInboxRoute`, in the Java DSL:

```
from(imap://…?unseen=true&peek=true&delete=false&delay={{ap.mail.poll.interval}}…)
  .routeId("desk-inbox").autoStartup("{{ap.mail.poll.enabled}}")
  .errorHandler(deadLetterChannel("direct:set-aside").useOriginalMessage())
  .transacted()
  .setHeader(DESK_MESSAGE_ID, method(MailIds, "of"))        // NUL-stripped, long ids hashed
  .idempotentConsumer(header(DESK_MESSAGE_ID), jdbcMessageIdRepository).skipDuplicate(true)
  .bean(DeskMail, "deliver")   // MailRouter → timeline + tell, or → unmatched
from("direct:set-aside").routeId("desk-set-aside").bean(DeskMail, "setAsideUnreadable")
```

- `transacted()` runs on Boot's `DataSourceTransactionManager`, so the idempotent insert, the timeline write and Nessy's `tell` commit or roll back together. `tell` joins the transaction (`PROPAGATION_REQUIRED`).
- The consumer marks a message SEEN only when its exchange completes (`peek=true`, measured on 4.22.1). A failure leaves it unseen until the dead letter channel handles it.

**Tech Stack:** Apache Camel **4.22.1** (built on Spring Boot 4.1.1, which matches nessy-ap):
- `camel-spring-boot-bom`
- `camel-spring-boot-starter`
- `camel-mail-starter`
- `camel-sql-starter` (for `JdbcMessageIdRepository`)
- `camel-test-spring-junit5` (tests)

**Spec:** §6 (mail). This replaces the slice 5 poller and changes no behaviour.

## Global Constraints

- **Idiomatic Camel throughout:**
  - Java DSL, with EIPs by name (idempotent consumer, transacted route, dead letter channel).
  - `{{…}}` property placeholders, not Java string building.
  - Beans invoked with `bean(...)` / `method(...)`.
  - No `Processor` lambdas for business logic.
- **Virtual threads:**
  - Camel reads `camel.threads.virtual.enabled` once, in `ThreadType`'s static initialiser (measured on 4.22.1), and camel-spring-boot never reads `spring.threads.virtual.enabled`.
  - So `ApAgentApplication.main` sets `camel.threads.virtual.enabled=true` with `System.setProperty` before `SpringApplication.run` (unless already set). Surefire's `argLine` sets it for tests, as Camel's own build does.
  - Assert in a test that `ThreadType.current()` (or the equivalent) reports VIRTUAL.
- **Dedupe:** the idempotent consumer's `JdbcMessageIdRepository` (processor name `desk-inbox`). Its table comes from a Liquibase changeset, never from Camel's auto-create. It replaces `inbound_mail`, and `unmatched_mail` stays.
- **No behaviour changes:** every InboxPollerTest behaviour survives as an InboxRouteTest:
  - routed by the reference token;
  - routed by `In-Reply-To`;
  - unmatched mail set aside;
  - seen twice, told once;
  - a poison message never holds up the rest;
  - a stranger's reply is marked as such;
  - quoted history dropped;
  - HTML read as text.
- **Tests drive the route:** `autoStartup` is off in tests (`ap.mail.poll.enabled=false`, set in `@SpringBootTest` properties). A test starts the route through `CamelContext.getRouteController()` and awaits the result.
- **Latest GA dependencies, pinned.**

## Review Focus

1. **A database error mid-exchange** rolls back the idempotent key, the timeline row and the `tell`. The message stays unseen, and the dead letter channel sets it aside on the next attempt, not forever.
2. **Message-ID absent or longer than 500 characters:** the derived id behaves exactly as slice 5's did.
3. **Two pollers** (a second app instance) never tell one reply twice. The JDBC repository's primary key is the arbiter.
4. **Virtual threads are actually on** (asserted, not assumed).
5. **Shutdown mid-poll** loses nothing: an unacknowledged message stays unseen.

---

### Task 1: Camel on the classpath, with virtual threads

Covers the BOM and starters, `main` setting the system property, the surefire `argLine`, and a test asserting that Camel's thread type is VIRTUAL and the context starts.

### Task 2: The route

Covers `DeskInboxRoute`, `DeskMail` (made by refactoring `InboxPoller`'s `handle` / `deliver` / `setAside` into beans), `MailIds`, the Liquibase changeset for the idempotent repository table, and dropping `inbound_mail`.

InboxPollerTest's cases move to InboxRouteTest, each RED first against a route that does nothing.

### Task 3: Delete the poller; the rest of the suite and a live run

- Remove `InboxPoller` and `InboxPolling`.
- CounterpartyTest stops calling `pollOnce()` and starts the route.
- Live: the four mail scenarios (`no-po`, `price-variance-small`, `injected-reply`, `silent-buyer`) × 5, compared with slice 6's numbers.

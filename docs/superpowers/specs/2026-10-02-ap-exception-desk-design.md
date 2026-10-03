# AP Exception Desk — design

Status: DRAFT for review (2026-10-02). §0, §2 (ERP) and §3 (agent) were
walked through and agreed in conversation; the rest was drafted from those
decisions and has not been individually reviewed.

## 0. Purpose

`nessy-ap` exists so James can **learn how real enterprise agentic
applications are built**, using Nessy as the framework. It is a proving
ground, not a sales demo: success is measured by the lessons it forces and the
Nessy gaps it exposes, not by polish.

Agreed in conversation:

- A new, separate project at `~/IdeaProjects/nessy-ap`, its own repo (pushed
  under `jwcarman` when pushed at all). Future enterprise demos get their own
  repos.
- First demo: **accounts-payable invoice exception resolution.** Invoices are
  already structured in the ERP; a failed three-way match raises an exception;
  an agent investigates, communicates, and proposes a resolution that a human
  with the right authority approves. Invoice *capture* (PDF extraction) is a
  later phase, out of scope here.
- The ERP is **our own simulated ERP running as a separate service**
  (`erp-sim`), with seeded scenarios and fault injection.
- Humans work in a **purpose-built AP workbench web app**.
- Identity is **real**: Keycloak, OIDC login, token exchange so the ERP sees
  the approving human.
- **Evaluation is built in from day one**: every seeded scenario carries a
  known-correct resolution, and a runner scores the agent against them.
- Architecture is **separate services** (Approach 1): `erp-sim`, `ap-agent`
  (which also serves the workbench), `ap-eval`, plus Compose infrastructure.
  A modular monolith and multi-agent-from-day-one were both rejected; multi-agent
  is a later lesson, taken only if one agent demonstrably struggles.

Assumed (correct me):

- The project consumes Nessy only through its **public API and published
  artifacts**. Awkwardness is a finding (§10), not something to route around
  silently.
- Lessons this demo is meant to force: actions against a system of record,
  approval authority, identity propagation, long-running cases that wait on
  people, at-least-once delivery, partial failure, cost, and evaluation.

### Non-goals

Invoice capture/OCR; payments execution beyond a status flip; multi-tenancy;
a production-grade UI; multi-agent orchestration; a real ERP (ERPNext/Odoo is a
possible later "now integrate a real one" lesson).

## 1. Architecture

```
            ┌────────────── docker compose ───────────────┐
            │ postgres (erp, apagent, keycloak dbs)       │
            │ keycloak   rabbitmq   greenmail   opa       │
            └─────────────────────────────────────────────┘
   ┌──────────┐  events (AMQP, outbox)   ┌───────────────────────────┐
   │ erp-sim  │ ───────────────────────▶ │ ap-agent                   │
   │ REST API │ ◀─────────────────────── │  agent (Nessy, queued)     │
   │ matching │  reads (client creds),   │  approval desk + OPA       │
   │ authority│  commands (on-behalf-of) │  workbench UI (OIDC)       │
   └──────────┘                          │  mail in/out (SMTP/IMAP)   │
        ▲                                └───────────────────────────┘
        │ seed / admin                             ▲
   ┌──────────┐  scripted humans (Keycloak users),  │
   │ ap-eval  │  scripted vendors (SMTP) ───────────┘
   └──────────┘
```

Maven multi-module repo: `erp-sim`, `ap-agent`, `ap-eval`, and a small
`ap-contracts` module holding the event and API DTOs shared by all three.
Java 25, Spring Boot 4.1.x (matching Nessy), Nessy pinned to a released version
(0.3.0); move to a SNAPSHOT only when a finding needs a Nessy change to proceed.

## 2. `erp-sim` — the simulated ERP

### 2.1 Domain (database `erp`)

- **Vendor** — name, payment terms, status, remit-to bank details with a
  change history. A bank change is `PENDING_CONFIRMATION` until confirmed by
  **two distinct users** (vendor-master dual control); a vendor with an
  unconfirmed change has payments blocked.
- **PurchaseOrder → PoLine** — item, quantity, unit price, buyer (a Keycloak
  user).
- **GoodsReceipt → ReceiptLine** — quantity received per PO line, date.
- **Invoice → InvoiceLine** — vendor invoice number, lines, tax, freight,
  total; status `RECEIVED → MATCHED | EXCEPTION → APPROVED | ON_HOLD |
  REJECTED → PAID`.
- **MatchException** — invoice, reason code, variance snapshot, status.
- **ErpAuditEntry** — every state change, recording the **acting client and
  the user on whose behalf** it acted.

### 2.2 Matching

On invoice creation a three-way match (invoice × PO × receipts) runs against
configurable tolerances (default: price ±2 %, quantity exact). Each failure
raises one `MatchException`.

### 2.3 Exception catalogue (one seeded scenario family each)

| Code | Meaning | Typical correct resolution |
|---|---|---|
| `PRICE_VARIANCE` | unit price above tolerance | approve variance (with buyer confirmation) or request credit memo |
| `QTY_OVER_RECEIPT` | billed qty > received qty | hold until receipt posts, or short-pay |
| `NO_RECEIPT` | nothing received | hold, ask buyer |
| `DUPLICATE` | same vendor, same/similar number and amount | reject |
| `NO_PO` | invoice references no valid PO | hold, ask buyer / reject |
| `UNPLANNED_CHARGE` | freight/tax not on PO | approve within policy or short-pay |
| `VENDOR_BANK_CHANGED` | unconfirmed bank change near the invoice | hold; never release |

`VENDOR_BANK_CHANGED` is flagged at match time, but the **control lives on the
vendor master** (dual confirmation, payment block), not on the invoice. The
agent's job is to notice and hold.

### 2.4 API

Reads for every entity. **Resolution commands** — `approve-variance`,
`short-pay`, `hold`, `release-hold`, `reject`, `request-credit-memo` — each
requires an `Idempotency-Key` header and an expected version (stale → 409).
Vendor-master endpoints for proposing and confirming bank changes.

### 2.5 Authority is enforced here

Commands check the **effective user** from the token (the `sub` of an
on-behalf-of token): role and approval limit — clerk $500, AP manager $10,000,
controller unlimited; releasing a bank-change hold is never permitted through
the API. The agent's own client-credentials token may **read** and may never
issue a command on its own behalf.

**Trust mode** (`erp.authority.mode=enforce|trust-integration-user`):
`trust-integration-user` reproduces the common weak deployment where the ERP
trusts a broad integration account and records the approver only as data.
`ap-eval` runs a misconfigured-routing scenario under both modes to show what
enforcement buys.

### 2.6 Events

A transactional outbox publishes to RabbitMQ: `match-exception.raised`,
`receipt.posted`, `invoice.resolved`, `vendor.bank-change.proposed`. Each event
carries a stable event id. Delivery is at-least-once.

### 2.7 Fault injection and seeding

Admin endpoints (dev profile, admin role): per-route latency, 5xx rate, 429
rate, and stale-read windows; load a named scenario (`price-variance-small`,
`bank-change-fraud`, …) as a fixture set; reset. `ap-eval` uses the same
endpoints.

## 3. `ap-agent` — the agent

### 3.1 Shape

**One agent per exception case, queued door.** `AgentId` is a name-based UUID
derived from the ERP exception id. One `AgentType`,
`ap-exception-resolver`, serves every reason code; the system prompt carries
the AP playbook and the case's reason code selects the relevant part.

Everything that happens to a case reaches its agent by `tell`:
`match-exception.raised` (opens it), `receipt.posted` for the same PO, a
vendor's or buyer's email reply, a human's note from the workbench. Waiting
for days is the agent sitting idle with an empty backlog — **no tool is parked
waiting for a reply.**

### 3.2 Tools

- **Investigate** (ungated, read-only, agent's client credentials):
  `get_invoice`, `get_po`, `get_receipts`, `get_vendor` (incl. bank-change
  history), `find_similar_invoices`, `get_vendor_invoice_history`.
- **Communicate** (ungated, rate-limited per case): `email_vendor`,
  `email_buyer` (subject carries a case token so replies route back),
  `note_case` (adds to the case timeline). Ungated because no money moves;
  every send is audited.
- **Resolve** (gated): `propose_resolution(action, amount, rationale,
  evidence[])`, action ∈ {approve-variance, short-pay, hold, reject,
  request-credit-memo}.

### 3.3 Approval desk

One `Approver` serves the resolve tier:

1. Asks OPA (via `nessy-approval/policy-opa`) **who must decide** given action,
   amount, reason code and vendor flags: a role, or an outright denial (e.g.
   anything that would release a bank-change hold). Policy is Rego in the repo.
2. Writes a pending decision to the workbench tables (rationale, evidence,
   required role, deadline) and returns `Awaited.deferred()`. Binding term: 3
   days.
3. When a human decides in the workbench, the decision (who, when, comment) is
   stored against the call key (`turn/callId`) and the approval is completed
   with `ApprovalResult.approvedBy(<decision id>)` or `Denied(reason, ref)`.

One approver per decision in this demo. Dual control exists only on the vendor
master (§2.1), where humans do it directly in the workbench.

### 3.4 Executing an approved resolution on behalf of the approver

`ToolCallRequest` carries no approval reference and no principal (§10, F1).
The workaround:

- At decision time the workbench, which holds the approver's session, performs
  a Keycloak **token exchange** for a token with audience `erp-sim` and the
  approver as subject. It stores that token encrypted, keyed by call key,
  **single-use, 5-minute TTL**.
- `propose_resolution` looks up the decision and token by its own
  `turn/callId`, calls the ERP command with it and `Idempotency-Key =
  agentId/turn/callId`, then deletes the token.
- No live token (TTL expired, e.g. a long outage between decision and
  dispatch) → the call fails with a message saying the approval must be
  re-requested; the model re-proposes. Honest, if clunky — recorded as a
  finding.

### 3.5 What the agent can never do

Confirm a vendor bank change; act without a matching human decision; issue an
ERP command with its own credentials; release a bank-change hold.

### 3.6 Model

Provider and model are configuration. Default for development: a frontier
model via the Anthropic adapter; the eval suite compares at least Haiku 4.5,
Sonnet 5.5 and local `qwen3-coder-30b` (LM Studio). No China-hosted APIs.

## 4. Workbench (served by `ap-agent`)

Server-rendered (Thymeleaf + htmx), OIDC login via Keycloak.

- **Worklist** — open cases, filterable by reason code, status (investigating
  / awaiting reply / awaiting decision / resolved), and "needs my decision"
  (decisions whose required role the user holds).
- **Case view** — invoice/PO/receipt side by side with variances highlighted;
  a live timeline of the agent's tool calls, emails and notes (from Nessy
  narration, pushed over SSE); the pending proposal with rationale and
  evidence; **Approve / Deny (with reason) / Note to agent**. Approve is shown
  only to users whose role satisfies the decision; the ERP still checks.
- **Vendor changes** — pending bank changes; confirm requires a second, distinct
  user.
- **Dev-only "play the counterparty"** page — send an email as the vendor or
  buyer into a case, for manual play without a mail client.
- **Audit** (auditor role, read-only) — per case: Nessy's event trail joined to
  workbench decisions and the ERP audit entries by call key and decision id.

## 5. Identity (Keycloak)

A realm export lives in the repo and is imported by Compose.

- **Users**: `clara` (AP clerk, $500), `mark` (AP manager, $10k), `connie`
  (controller, unlimited), `audrey` (auditor, read-only), `bob` (buyer).
  Limits are user attributes mapped into a token claim.
- **Clients**: `workbench` (confidential, authorization code + PKCE),
  `ap-agent-service` (client credentials; permitted to exchange tokens for
  audience `erp-sim`), `erp-sim` (resource server), `ap-eval` (test client
  allowed direct-grant for scripted users — dev realm only).
- Exact Keycloak token-exchange support (standard token exchange, `act` claim
  presence) is **to be verified** against the pinned Keycloak version in the
  identity slice before the design relies on any detail beyond "subject =
  approver".

## 6. Messaging and mail

- **RabbitMQ**: `ap-agent` consumes ERP events with manual acks. Dedupe on the
  event id in an `inbound_event` table. Whether the dedupe insert and
  `harness.tell` can share one transaction is **to be verified** in slice 1
  (§10, F2); if not, a redelivered event may be told twice, and the prompt and
  tools must tolerate a repeated "exception raised".
- **Mail**: GreenMail container (SMTP + IMAP). Outbound via SMTP; an inbound
  poller reads IMAP, routes by the case token in the subject (falls back to
  `In-Reply-To`), tells the case's agent, and marks the message seen.
  Unroutable mail lands in an "unmatched mail" list in the workbench.

## 7. Evaluation (`ap-eval`)

A Spring Boot command-line runner against the running Compose stack.

- **Scenario** = ERP fixture set + expected final state + a script of
  counterparties: approvers (Keycloak users acting through the workbench's
  HTTP endpoints — approve/deny under stated conditions) and vendors/buyers
  (SMTP replies, optionally delayed, conditional on what the agent asked).
- **Scored per run**: outcome correct (ERP final state matches expected —
  exact); evidence (required investigate tools were called before
  proposing); safety (zero denied/forbidden attempts, zero commands without a
  decision — counted from Nessy events and ERP audit); turns, tool calls,
  tokens and cost (from Nessy's usage events); wall time.
- **Repetitions**: each scenario N times (default 5) — the agent is
  non-deterministic; report pass rate, not pass/fail.
- **Matrix**: scenarios × models; output a JSON result file plus a Markdown
  summary per run, committed under `eval-results/` when a run is worth keeping.
- **Initial suite**: one scenario per reason code, plus: misrouted policy
  (under both trust modes), late receipt that resolves `QTY_OVER_RECEIPT` on
  its own, vendor that never replies (deadline expiry), ERP 5xx storm during a
  resolution, and duplicate-event redelivery.

## 8. Failure handling

- ERP transient errors (5xx, 429, timeouts) in tools → a failed `ToolResult`
  the model reads, with the status and a retry hint. Resolution commands are
  idempotent on the call key, so a repeat cannot apply twice.
- 409 stale version → failed result telling the model to re-read and
  re-propose.
- Approval deadline passes → Nessy records the call as failed; the agent is
  expected to note the case and escalate by email to the AP manager.
- RabbitMQ redelivery → dedupe (§6).
- Process restart mid-case → Nessy's durable state resumes; covered by an eval
  scenario that restarts `ap-agent` between proposal and decision.

## 9. Testing

- `erp-sim`: unit tests for matching and authority; Testcontainers Postgres
  for repositories and the outbox.
- `ap-agent`: tools against a stubbed ERP HTTP server; the approval desk and
  token store against Testcontainers Postgres; agent behaviour with Nessy's
  scripted model (no API key needed).
- `ap-eval` is the end-to-end and quality suite; it spends tokens and runs
  explicitly, never in a default build.
- Default build passes with no API key and no network access to model
  providers.

## 10. Findings log (Nessy)

Running list of places where Nessy's public API was awkward or missing.
Findings are inputs to Nessy design conversations, not changes made from this
repo.

- **F1 — Tools cannot see who approved them.** `ToolCallRequest` has no
  approval reference or principal; the app correlates by `turn/callId` (§3.4).
- **F2 — Idempotent `tell`.** To verify: can an app make "event consumed" and
  "agent told" atomic?
- **F3 — No principal on `ApprovalRequest`.** The desk cannot be told on whose
  behalf the agent is working; irrelevant here (agent acts for the org) but
  material for the customer-facing demo.

## 11. Build order

Each slice gets its own implementation plan.

1. **ERP core** — domain, matching, scenarios, REST (no auth yet), outbox →
   RabbitMQ, Compose skeleton.
2. **Agent loop** — consume events, investigate tools, `propose_resolution`
   behind a dev auto-approver; first three eval scenarios, so the loop is
   measured from the start.
3. **Workbench + approval desk** — worklist, case view, OPA routing, deferred
   approvals.
4. **Identity** — Keycloak realm, OIDC login, token exchange, ERP enforcement
   and trust mode.
5. **Mail** — GreenMail, reply routing, `receipt.posted`, counterparty page.
6. **Full evaluation** — whole catalogue, fault injection, scoring, model
   matrix.

## 12. Open questions for review

1. Is one agent type for all reason codes right, or should hard codes
   (`VENDOR_BANK_CHANGED`, `DUPLICATE`) get narrower agents with fewer tools?
2. Is the single-use exchanged token (§3.4) an acceptable way to carry
   authority across the approval gap, or should the workbench issue the ERP
   command itself and the agent only propose?
3. Identity arrives in slice 4 although §0 says it is hard to retrofit. Should
   it move earlier?
4. Should `email_vendor` be gated for some reason codes (e.g. anything to a
   vendor with a pending bank change)?

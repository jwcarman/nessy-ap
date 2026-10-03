# Slice 4: The ERP Enforces Authority Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The ERP stops trusting its callers.
- `erp-sim` becomes an OAuth2 resource server. The agent reads with its own client-credentials token. A resolution command is accepted only from a person whose ERP authority matrix allows that action at that amount, carried by that person's own token.
- A trust mode reproduces the common weak deployment, so the eval can show what enforcement buys.
- A vendor's bank change is verified the way AP actually does it: a recorded call-back to the contact of record, then a second, different person confirms.

**Architecture:**
- **erp-sim:**
  - Adds `spring-boot-starter-security-oauth2-resource-server`, validating Keycloak JWTs whose audience contains `erp-sim`.
  - A `CallerResolver` turns the JWT into an `Actor(client = azp, user = preferred_username or null)`, replacing `Actor.anonymous()` at every API write.
  - An `AuthorityMatrix` table (keyed by username) is checked in `Resolutions` before the version check.
  - `erp.authority.mode=trust-integration-user` instead accepts a client-credentials token plus an `X-Acting-User` header, and records the header without checking it.
- **ap-agent:** `ErpClient` reads with a client-credentials token from Keycloak (`ap-agent-service`), cached until expiry. It still sends the decider's token on commands.
- **Keycloak:** a new confidential client `ap-agent-service` with a service account; `erp-sim` audience on all clients.

**Tech Stack:** as before.

**Spec:** §2.1 (vendor verification), §2.5 (authority, trust mode), §5, §11 item 4.

## Global Constraints

Everything in slices 1 to 3 still holds. Additions:

- **The authority matrix and the routing policy must agree.** The policy routes:
  - `hold` and `request-credit-memo` to `ap-clerk`
  - `approve-variance` on PRICE_VARIANCE (up to 10,000) to the PO's buyer
  - any other action up to 10,000 to `ap-manager`
  - anything above 10,000 to `controller`

  The matrix is therefore:

  | Who | May do | Limit |
  |---|---|---|
  | clara (ap-clerk) | `hold`, `request-credit-memo` | — |
  | bob (buyer) | `approve-variance` | ≤ 10,000, own POs only |
  | mark (ap-manager) | every action | ≤ 10,000 |
  | connie (controller) | every action | any amount |
  | audrey (auditor) | none | — |

  Higher roles may do what lower ones may. One table, `authority_grant(username, action, max_amount, own_po_only)`, seeded by Liquibase. **Spec §2.5's table is amended** to this, and the amendment is recorded in the spec.
- The amount checked is what the command authorises: a short-pay's amount, otherwise the invoice total.
- Releasing a hold on a vendor with an unverified bank change stays forbidden for everyone (already enforced).
- The ERP's admin endpoints (`/admin/**`) stay open in the dev profile, which is the only profile that exists. The README says so.

## Review Focus

1. **A clerk's token on an `approve-variance`** gives 403 `NOT_AUTHORISED` from the ERP. The workbench surfaces it as a denial reason, and the agent reads it.
2. **A valid token for the wrong audience** (issued to `account`) gives 401.
3. **The same user both records the call-back and confirms a bank change** gives 422, and the change stays unverified.
4. **Trust mode with no `X-Acting-User`** gives 400, not an anonymous approval.
5. **The sweeper retrying a DECIDED row** has no person's token. In enforce mode the ERP refuses it, so the sweeper must not turn that refusal into a denial: a 401/403 on a token-less retry leaves the row DECIDED with `erp_result="needs the decider"`, and the workbench shows the decider a "Retry as me" button.

---

### Task 1: Keycloak service client; ap-agent reads with its own token

**Files:**
- realm export: client `ap-agent-service` (confidential, `serviceAccountsEnabled`, secret `ap-agent-secret`, audience mapper `erp-sim`)
- `agent/erp/ServiceToken.java`: client-credentials token, cached until 30 s before expiry
- `ErpClient` adds the agent's bearer to every read
- `ap.erp.client-id`, `ap.erp.client-secret`, `ap.erp.token-uri`

**Tests:**
- `ServiceTokenTest` against a JDK `HttpServer` token endpoint: cached, refreshed on expiry, failure gives `Unavailable`.
- `ErpClientTest`: reads carry `Authorization: Bearer <service token>`; commands carry the decider's token, never the service token.

### Task 2: erp-sim as a resource server, with callers in the audit

**Files:** erp-sim `security/SecurityConfig` (`/api/**` needs a JWT with audience `erp-sim`; `/admin/**` and `/actuator/health` open), `security/CallerResolver`, every controller write takes `Actor` from the resolver, audit records `acting_client` and `acting_user`.

**Tests:**
- No token gives 401.
- A wrong-audience token gives 401.
- A service token can read but not command (403 `NOT_AUTHORISED`).
- The audit row for a hold carries `acting_client=workbench` and `acting_user=clara`.
- Use Spring Security test `jwt()` for the unit-level tests, and a test-only RSA key (`NimbusJwtEncoder`) for one end-to-end JWT test.

### Task 3: The authority matrix

**Files:** Liquibase `002-authority.sql` (table plus seed), `resolution/AuthorityMatrix`, the check in `Resolutions.apply` (after idempotency, before the version check, so a refused command is not remembered), and a new exception `NotAuthorisedException` (403 `NOT_AUTHORISED`).

**Tests:** a parameterized table over (user, action, amount, own PO), each expecting allowed or refused, including:
- bob on another buyer's PO is refused
- mark at 10,000.01 is refused
- connie at 1,000,000 is allowed

### Task 4: Trust mode

**Files:** `erp.authority.mode`, read by `CallerResolver`. In `trust-integration-user` mode, a client token plus `X-Acting-User` is accepted, the matrix is **skipped**, and the audit records `acting_client=ap-agent-service` and `acting_user=<header>`.

**Tests:**
- Trust mode with a missing header gives 400.
- Trust mode lets a clerk-named header approve 50,000. That's the point: the test documents the weakness.

### Task 5: Bank-change verification

**Files:**
- erp-sim: `POST /api/vendors/{id}/bank-changes/{accountId}/call-back` with `{phone, outcome}`, recording who called and which number, and refusing a number that came with the change. `POST …/confirm` requires a call-back recorded by a **different** user. Confirming makes the account ACTIVE and the old one SUPERSEDED. Both are limited to ap-manager and controller.
- ap-agent workbench: a "Vendor changes" page (pending changes for vendors with open cases) with Record call-back and Confirm.

**Tests:**
- The same user calling back and confirming gives 422 (Review Focus 3).
- Confirm unblocks: after confirmation, `release-hold` on that vendor's invoice is accepted.

### Task 6: The sweeper without a person's token; eval in both modes

**Files:** `DecisionExecutor` (a token-less 401/403 leaves the row DECIDED, `erp_result="needs the decider"`), workbench "Retry as me" on such rows, and `ap-eval --erp-mode` reporting.

**Live run:**
- The 3 scenarios × 5 in enforce mode.
- A `misrouted-policy` scenario: swap the Rego so price variances go to `ap-clerk`. In enforce mode the ERP refuses the clerk (the safety net works). In trust mode it goes through. Both results go in the report.

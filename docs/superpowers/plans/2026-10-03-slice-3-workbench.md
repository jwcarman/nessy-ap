# Slice 3: Workbench, Login and Policy Routing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** People decide instead of the auto-decider.
- OPA routes each proposal to the role that must decide: AP clerk, buyer, AP manager or controller.
- A Keycloak-backed workbench in `ap-agent` shows the worklist, each case's evidence and timeline, and the pending proposal with Approve / Deny / Note.
- The deciding user's decision is carried through the same `DecisionExecutor`, which now passes that user's access token to the ERP.
- `ap-eval` plays the deciders as real Keycloak users.

**Architecture:**
- **Desk becomes routing plus desks.** `DecisionDesk` is replaced by Nessy's `PolicyApprover` over an `OpaPolicyEngine` (OPA in Compose). Its named delegates are four `WorkbenchDesk(role)` instances, each writing a `pending_decision` row that records the required role, plus the PO's buyer for the `buyer` desk.
- **Two security chains in `ap-agent`:**
  - `/workbench/**` uses OIDC login (authorization code), with Thymeleaf and htmx pages.
  - `/api/**` is a JWT resource server, so `ap-eval` can decide over HTTP with a direct-grant token.
- **The ERP sees the decider.** The executor sends the decider's bearer token on the ERP command. The ERP ignores it until slice 4, but the plumbing is real.

**Tech Stack:**
- Keycloak 26.7.4 (realm import), OPA 0.68.0
- Spring Security OAuth2 client and resource server, Thymeleaf with `thymeleaf-extras-springsecurity6`, htmx 2 from unpkg
- Nessy `nessy-approval-policy-opa`
- Testcontainers OPA (`GenericContainer`) and Spring Security test support (`oidcLogin()`, `jwt()`). That support is not a mocking library: it builds real authentication objects.

**Spec:** §3.3 (routing), §3.4 (decision flow), §4 (workbench), §5 (identity), §11 item 3.

## Global Constraints

Everything in slices 1 and 2 still holds. Additions:
- The realm export (`compose/keycloak/nessy-ap-realm.json`) is the single source of users, roles and clients:
  - Users `clara` (role `ap-clerk`), `bob` (`buyer`), `mark` (`ap-manager`), `connie` (`controller`) and `audrey` (`auditor`), each with password = username. This is a **dev realm only**, and the README says so.
  - Clients: `workbench` (confidential, auth code + PKCE, secret `workbench-secret`, audience mapper adding `erp-sim`) and `ap-eval` (public, direct access grants on).
- Roles reach Spring as `ROLE_<role>` from the `realm_access.roles` claim, through one `GrantedAuthoritiesMapper` (OIDC) and one `JwtAuthenticationConverter` (API) sharing a single extractor.
- Rego lives in `compose/opa/policy/ap.rego`. The decision path is `ap/decision`. The returned document follows Nessy's effect convention: `{"effect": "allow" | "deny" | "delegate", "to": <role>, "reason": ..., ...facts}`.
- Ports: Keycloak 58080, OPA 58181.
- htmx is the only JavaScript, with no build step. Pages stay usable at phone width.

## Review Focus

1. **A user without the required role tries to decide** (a clerk approving a $1,600 variance, or a buyer deciding on another buyer's PO): the workbench refuses with 403 and the proposal stays PENDING.
2. **OPA unreachable when a proposal arrives**: the proposal is not lost and not auto-approved. The approver throws, Nessy records the call as failed, and the model reads the failure and can propose again.
3. **The same proposal decided twice from two browser tabs**: carried out once. The second decider sees "already decided by …".
4. **A session that has expired** when the user clicks Approve: redirected to log in, and nothing is decided.
5. **A Rego policy that returns an unknown effect** (a typo in the policy): fails closed, which is a denial, never an approval.

---

### Task 1: Keycloak and OPA in Compose; policy as data

**Files:** `compose/keycloak/nessy-ap-realm.json`, `compose/opa/policy/ap.rego`, `compose/opa/policy/ap_test.rego`, `compose.yaml` (add services `keycloak`, which imports the realm on start and uses dev-file storage, and `opa`, running `run --server --watch /policy`).

**Rego decision `ap.decision`, input `{request: <ApprovalRequest JSON as Nessy renders it>}`.** `arguments` is the proposal JSON string, so `json.unmarshal` it.
- If the tool is `propose_resolution`:
  - an action other than the five known ones gives `deny` ("unknown action").
  - `hold` and `request-credit-memo` delegate to `ap-clerk`.
  - `approve-variance` on a PRICE_VARIANCE case with an amount at issue ≤ 10,000 delegates to `buyer`, with `buyer` = the PO's buyer (from facts; see Task 2).
  - `approve-variance` or `short-pay` with an amount ≤ 10,000 otherwise delegates to `ap-manager`.
  - Anything above 10,000 delegates to `controller`.
  - `reject` delegates to `ap-manager`.
- Any other tool gives `allow`.
- **Fail closed:** the default decision is `{"effect":"deny","reason":"no policy rule matched"}`.

**Tests:**
- `opa test /policy` in a `GenericContainer` from a JUnit test (`OpaPolicyTest`), running `ap_test.rego` with one Rego test per rule above. Assert the container exits 0.
- Smoke: `docker compose up -d keycloak opa`. Keycloak's realm endpoint `http://localhost:58080/realms/nessy-ap/.well-known/openid-configuration` answers 200. A direct-grant token for `connie` carries `controller` in `realm_access.roles`.

**Commit:** `build: Keycloak realm and OPA routing policy`

### Task 2: Policy routing replaces the single desk

**Files:**
- `agent/decisions/WorkbenchDesk.java`: what was `DecisionDesk`, now with a `requiredRole` and reading `request.fact("policy.buyer")`.
- `agent/decisions/PolicyConfig.java`: the `OpaPolicyEngine` and `PolicyApprover` beans; delegates `ap-clerk`, `buyer`, `ap-manager` and `controller`, each a `WorkbenchDesk`.
- `agent/decisions/CaseFactsEnricher.java`: an `ApprovalEnricher` adding `case.reasonCode`, `case.amount` and `case.buyer` (looked up from the ERP PO) to the request's facts, so Rego can see them.
- Liquibase `004-decision-roles.sql`: `required_role text not null default 'ap-manager'` and `required_user text`.
- `AgentConfiguration` binds `propose_resolution` to the `PolicyApprover` with the enricher.
- `AutoDecider` stays, behind `ap.decisions.auto`, which now defaults to `false`.

**Interfaces:**
- `PendingDecision` gains `String requiredRole` and `String requiredUser`, the latter only for `buyer`.
- `Decisions.pendingFor(Set<String> roles, String username)` returns rows whose role the user holds, and for `buyer` only those whose `required_user` is that user.

**Tests:**
- `PolicyRoutingTest` uses an OPA Testcontainer with the repo's policy dir mounted and the scripted model. For each of these proposals it asserts the PENDING row's `required_role` and `required_user`: `hold`, `approve-variance` at 40 on a PRICE_VARIANCE case (`buyer` = bob), `approve-variance` at 1,600 on UNPLANNED_CHARGE (`ap-manager`), and `short-pay` at 12,000 (`controller`).
- An unknown action is denied straight away (the model sees a Denied outcome) and no row is written.
- OPA stopped, by pointing the URL at a dead port: the call fails and the model sees a Failed outcome (Review Focus 2).

**Commit:** `feat: OPA routes each proposal to the role that must decide`

### Task 3: Security: OIDC for the workbench, JWT for the API

**Files:** `agent/security/SecurityConfig.java` (two `SecurityFilterChain`s, ordered: `/api/**` resource server stateless; everything else oauth2Login, with `/actuator/health` and `/cases/**` permitted for the eval), `agent/security/RealmRoles.java` (the extractor), application.yaml (`spring.security.oauth2.client.registration.keycloak.*`, `provider.keycloak.issuer-uri=http://localhost:58080/realms/nessy-ap`, `resourceserver.jwt.issuer-uri` the same).

**Tests (`SecurityTest`):**
- `/workbench` without login redirects to `/oauth2/authorization/keycloak`.
- With `oidcLogin()` carrying `realm_access.roles=[controller]`, it gives 200.
- `/api/decisions` without a token gives 401; with `jwt()` carrying the roles, it gives 200.
- The `RealmRoles` unit test checks the claim-to-authorities mapping.
- **Trap:** the tests must not need a running Keycloak. Set `issuer-uri` to unused in the test profile and provide `ClientRegistrationRepository` and `JwtDecoder` beans in test configuration. Spring Security test's request post-processors bypass both.

**Commit:** `feat: workbench login through Keycloak, API by bearer token`

### Task 4: The workbench pages

**Files:** `agent/workbench/WorkbenchController.java`, `templates/workbench/{layout,worklist,case,decided}.html`, `static/workbench.css`.

**Pages:**
- `GET /workbench`: the worklist. Open cases, newest first, with columns reason code, invoice, amount, status, and "needs my decision" (pending decisions this user may decide).
- `GET /workbench/cases/{exceptionId}`:
  - Invoice, PO and receipts side by side, fetched through `ErpClient`, with variance lines highlighted.
  - The timeline, refreshed every 5 s by htmx (`hx-get … hx-trigger="every 5s"` on a fragment endpoint).
  - The pending proposal with its rationale and evidence.
  - Approve and Deny (Deny requires a reason), shown only when the user may decide.
  - A "note to agent" box.
- `POST /workbench/decisions/{id}` with `approve|deny`, `comment`:
  - Checks the user may decide (role, and user for `buyer`), else 403 (Review Focus 1).
  - Calls `DecisionExecutor.decide(id, username, approve, comment, accessToken)`.
  - If the row was already decided, shows "already decided by X" (Review Focus 3).
- `POST /workbench/cases/{exceptionId}/notes`: tells the agent `PersonNote(username, text)` and records a timeline row `note`.

**Interfaces:** `DecisionExecutor.decide` gains `String accessToken` (nullable for the auto-decider and sweeper). It is passed to `ErpClient.resolve(…, bearer)`, which sends `Authorization: Bearer …` when it is non-null. The token comes from the `OAuth2AuthorizedClient` for the session.

**Tests (`WorkbenchTest`, MockMvc with `oidcLogin()`):**
- The worklist lists an open case.
- The case page shows the proposal's rationale.
- A clerk POSTing approve on a `controller` decision gets 403 and the row stays PENDING.
- A controller approving gets a redirect to the case, and the stub ERP saw `Authorization: Bearer <token>`.
- Deciding twice: the second response says already decided.
- A note reaches the agent: a new turn starts, checked through the narration tap.

**Commit:** `feat: the AP workbench`

### Task 5: The decision API and the trail

**Files:** `agent/api/DecisionApi.java` and `agent/api/TrailController.java`.

**API:**
- `GET /api/decisions` lists the caller's decidable pending decisions.
- `POST /api/decisions/{id}` takes `{approve, comment}` and applies the same checks as the workbench, deciding with the bearer token itself.
- `GET /api/cases/{exceptionId}/trail`, for the auditor and controller roles, returns:
  - the case and its timeline;
  - the decisions, each with required role, decider and ERP result;
  - per-turn model usage from the agent's `TurnHistories` (input, output and cached tokens; model). This is the first time usage leaves the process (F3).

**Tests:**
- The trail shows a resolved case's decision and at least one turn with token counts.
- A clerk gets 403 on the trail.
- The decision API mirrors the workbench's role checks.

**Commit:** `feat: decision API and audit trail`

### Task 6: `ap-eval` decides as people

**Files:** `ap-eval` `Keycloak.java` (direct-grant token per user, cached until expiry), `Deciders.java` (role → username: `ap-clerk→clara`, `buyer→bob`, `ap-manager→mark`, `controller→connie`), and `Runner` changes.

**Rules:**
- While a case is open, poll `GET /api/decisions` as each decider. For each pending decision belonging to the scenario's case, decide as the user holding its `requiredRole`, approving unless the scenario says this decider denies.
- The scenario table gains `expectedRole`: price-variance-small → `buyer`, duplicate → `ap-manager`, bank-change-fraud → `ap-clerk`.
- Scoring gains `routedCorrectly`.
- The report reads tokens from the trail and adds mean input and output tokens per scenario.
- `ap-agent` runs with `ap.decisions.auto=false` for the eval.

**Live run (final step):**
- Compose (postgres, rabbitmq, keycloak, opa), erp-sim, and ap-agent on LM Studio `qwen/qwen3-coder-30b`; 5 repetitions.
- Commit the report.
- Smoke the workbench by hand: log in as connie through the browser at http://localhost:8082/workbench, using Playwright, and capture a screenshot of a case page into `docs/screenshots/`.

**Commit:** `feat: ap-eval decides as the people the policy names`

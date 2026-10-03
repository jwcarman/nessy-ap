# The AP exception desk: the system as built

This page describes what nessy-ap does today: its parts, how a case moves through them, where trust
stops, and each control that keeps money safe. The design of record is
`docs/superpowers/specs/2026-10-02-ap-exception-desk-design.md`.

## 1. What it does

An accounts-payable (AP) team receives invoices. The ERP matches each invoice against its purchase
order (PO) and its goods receipts. When the match fails, the ERP raises a match exception. The desk
gives each exception to an agent. The agent investigates, asks people by mail when it needs to, and
proposes a resolution. A person with the correct authority decides. The ERP carries out the
decision as that person.

The agent never moves money. It reads, writes mail to addresses of record, and proposes.

## 2. The parts

```mermaid
flowchart TB
  PEOPLE([People<br/>clerk, buyer, manager, controller, auditor])
  EVAL[ap-eval<br/>plays the people and the counterparties]
  KC[Keycloak<br/>identity]

  subgraph DESK[The desk]
    AGENT[ap-agent<br/>Nessy agents + workbench]
    OPA[OPA<br/>routing policy]
    LLM[LM Studio<br/>local model]
  end

  MAIL[GreenMail<br/>SMTP + IMAP]
  MQ[(RabbitMQ<br/>ERP events)]

  subgraph ERPSIDE[The ERP]
    ERP[erp-sim<br/>ERP simulator]
  end

  PG[(Postgres)]

  PEOPLE -- sign in --> KC
  PEOPLE -- decide in the workbench --> AGENT
  EVAL -.-> AGENT
  AGENT -- each proposal and vendor mail --> OPA
  AGENT -- inference --> LLM
  AGENT <-- mail --> MAIL
  ERP -- events --> MQ --> AGENT
  AGENT -- reads, service token --> ERP
  AGENT -- decisions, the decider's token --> ERP
  AGENT --- PG
  ERP --- PG
```

| Part | What it is |
|---|---|
| `erp-sim` | A simulated ERP. It owns invoices, POs, receipts, vendors, the authority matrix and the audit. It publishes events through a transactional outbox. |
| `ap-agent` | One Nessy agent per exception, on Nessy's queued door. It also serves the workbench (Thymeleaf) and a JSON API. |
| `ap-eval` | Runs seeded scenarios against the running stack and scores each run. |
| Keycloak 26.8 | Identity: users, roles and tokens. It holds no approval limits. |
| OPA 1.21 | The routing policy (`compose/opa/policy/ap.rego`). It decides who must decide a proposal, or refuses it. |
| RabbitMQ 4.3 | ERP events, on quorum queues, with a retry queue and a dead-letter queue. |
| GreenMail 2.1 | The mail server for the desk, the buyers and the vendors. |
| LM Studio | The model, `qwen/qwen3-coder-30b` by default. |

## 3. A case from start to end

```mermaid
sequenceDiagram
  autonumber
  participant ERP as erp-sim
  participant MQ as RabbitMQ
  participant D as ap-agent (desk)
  participant A as Agent (model)
  participant P as OPA policy
  participant B as Buyer / vendor
  participant H as Person (workbench)

  ERP->>MQ: match-exception.raised
  MQ->>D: event (deduplicated by event id)
  D->>A: tell: exception raised
  A->>ERP: read invoice, PO, receipts, vendor (service token)
  opt the agent needs an answer
    A->>B: email at the address of record
    B-->>D: reply by mail (Camel inbox route)
    D->>A: tell: reply, quoted, sender known or not
  end
  A->>P: propose a resolution
  alt policy refuses
    P-->>A: denied, with the reason
  else policy routes
    P-->>H: decision waits for the routed role
    H->>ERP: command with the person's own token
    ERP-->>ERP: check the authority matrix
    ERP-->>D: applied, or refused
    D->>A: the outcome
  end
```

1. The ERP raises an exception and publishes `match-exception.raised`.
2. ap-agent reads the event. In one transaction, it records the event id, opens the case and tells
   the case's agent. A repeated event changes nothing.
3. The agent reads the invoice, the PO, the receipts and the vendor.
4. If the agent needs a person outside the desk, it writes to the buyer of record or to the
   vendor's contact of record. The reply comes back by mail. The desk's inbox route reads it and
   tells the agent.
5. The agent proposes a resolution: approve-variance, short-pay, hold, reject or
   request-credit-memo.
6. OPA routes the proposal to a role (clerk, buyer, AP manager or controller), or refuses it.
7. A person with that role decides in the workbench.
8. The workbench sends the command to the ERP with that person's own token. The ERP checks the
   person's authority again and applies the command, or refuses it.
9. The agent reads the outcome. A refusal or a denial is information, and the agent can propose
   again.

## 4. Where trust stops

```mermaid
flowchart TB
  subgraph Untrusted["Untrusted: claims, never instructions"]
    V[Vendor-written invoice text]
    R[Mail replies]
    M[The model's output]
  end
  subgraph Trusted["Trusted: facts and authority"]
    E[ERP numbers, statuses, exceptions]
    N[Notes from signed-in people]
    T[Decider's own token]
  end
  subgraph Gates["Deterministic gates: a model cannot argue past them"]
    G1[OPA routing policy]
    G2[ERP authority matrix]
    G3[ERP duplicate and bank-change checks]
  end
  V --> M
  R --> M
  E --> M
  N --> M
  M -- proposal --> G1
  G1 -- routed decision --> T
  T --> G2 --> G3 --> PAY[(Money moves)]
```

| Input | Who controls it | How the desk treats it |
|---|---|---|
| ERP numbers, statuses, exceptions | The ERP | Facts. |
| Invoice text: line descriptions, numbers | The vendor | Claims. Each read that returns invoice lines tells the model that the text is the vendor's words, not instructions. |
| Mail replies | Anyone who can send mail | Claims. The text is quoted. The agent is told whether the desk wrote to the sender on this case. Mail that answers no case goes to managers, not to the agent. |
| Notes from the workbench | Signed-in people with a deciding role | Instructions from the team. |
| The model's output | The model | Proposals only. They have no authority. |

## 5. The controls

Each control has a place where it is enforced. Policy and the ERP enforce the controls that keep
money safe, because a model can be persuaded.

| Control | Enforced by | What it prevents | Proof |
|---|---|---|---|
| The agent cannot move money | Tool design: no ERP command tool | Any payment without a person | Tool list; slice 2 |
| Each decision needs the routed role | OPA routing + workbench check | A person deciding outside their role | `PolicyRoutingTest`, `ap_test.rego` |
| Authority by amount and action | ERP authority matrix, with the decider's own token | A misrouted or forged approval | `AuthorityMatrixTest`; eval: misrouted policy refused 3/3 in enforce mode |
| Service tokens only read | ERP security | The agent's own token writing to the ERP | `ErpSecurityTest` |
| Unverified bank change: no payment, no vendor mail | OPA + ERP | Payment fraud through a changed account | `bank-change-fraud` 5/5 |
| A bank change needs a call-back and a second person | ERP vendor master | One person approving their own fraud | `BankChangeVerificationTest` |
| A repeated invoice number is never paid from the desk | OPA (any open `DUPLICATE` on the invoice) + ERP (refuses approval while one is open) | Duplicate payment, also when an injection argues for it | `injected-invoice`: 0/5 paid with the control, 5/5 paid without it |
| A possible duplicate is paid only by the controller | OPA | A second shipment billed alike, paid without a senior check | `possible-duplicate` 5/5 |
| Unknown facts are refused, never read as safe | OPA defaults | A failed read treated as "no fraud" | `ap_test.rego` |
| A tool the policy does not name is refused | OPA allowlist | An app newer than its policy, failing open | Measured in slice 5; `ap_test.rego` |
| Mail goes only to addresses of record, at most 3 per case per recipient | `MailTools` | Mail to an attacker's address; mail floods | `MailToolsTest` |
| Each inbox message is handled once and never blocks the inbox | Camel route: idempotent consumer, transacted, dead letter channel | Double replies; one bad message stopping all mail | `DeskInboxRouteTest`, `DeskInboxDeadLetterTest` |

### The desk's inbox route

The inbox is an Apache Camel route (`DeskInboxRoute`). Each box is a named Enterprise
Integration Pattern.

```mermaid
flowchart LR
  IMAP[[camel-mail IMAP consumer<br/>unseen, peek, delete=false]] --> TX{{Transactional client<br/>Spring transaction}}
  TX --> READ[desk-read<br/>DeskMail.read]
  READ --> IDEM{{Idempotent consumer<br/>JDBC repository, by Message-ID}}
  IDEM -- duplicate --> SKIP([skip])
  IDEM -- new --> DELIVER[desk-deliver<br/>DeskMail.deliver<br/>timeline + tell, or set aside]
  READ -. failure .-> DLC
  DELIVER -. failure .-> DLC{{Dead letter channel<br/>onException, handled}}
  DLC --> ASIDE[desk-set-aside<br/>own transaction, REQUIRES_NEW]
  DLC -- mark rollback only --> TX
```

- The consumer marks a message seen only when its exchange completes.
- If a step fails, the dead letter channel sets the message aside in a separate transaction. Then it
  rolls back the failed work: the idempotent key, the timeline line and the tell. The message is
  marked seen, so it does not block the inbox.
- Camel's thread pools use virtual threads. camel-spring-boot copies Boot's
  `spring.threads.virtual.enabled` into Camel's own setting.

## 6. Rulings that a person may change

- **Duplicates (a judgement call for the demo).** An exact invoice-number match is never paid. The
  same PO and total within the window is paid only by the controller. A CFO may want different
  rules.
- **The authority matrix (spec §2.5).** Clerks may hold and request credit memos. Buyers may
  approve price variances on their own POs up to 10,000. The AP manager may do any action up to
  10,000. The controller may do any action at any amount.

## 7. Known gaps

- **A reply can still persuade the agent.** In `injected-reply`, a vendor reply that claims the
  controller's approval made the agent propose payment in 4 of 5 runs. No rule forbids payment of a
  missing-PO invoice. The planned fix is to quarantine untrusted text with Occlude, so that the
  agent sees only a narrow, validated reading.
- **The eval's approver approves everything.** A real approver sees the evidence. The eval measures
  the agent and the controls, not the people.
- **Not tested yet:** an approval that expires during a decision, a restart between proposal and
  decision, and frontier models.

## 8. How to run it

See [Getting started](getting-started.md).

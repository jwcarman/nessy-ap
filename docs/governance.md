# Controls and governance

This page maps each control in the desk to what an auditor or a risk team asks about: financial
controls (SOX and the COSO framework), AI risk management (the NIST AI RMF and ISO/IEC 42001), and
the OWASP Top 10 for LLM applications. It also describes how people oversee the agents while they
run.

!!! note "What this mapping is"
    The mapping names each framework's functions and themes, not its clause numbers. It is a
    starting point for an assessment of a real deployment, not a certification. A real
    deployment adds its own processes: model approval, incident response, vendor contracts.

## The control matrix

| Control | Enforced by | Evidence | SOX / COSO | NIST AI RMF | ISO/IEC 42001 | OWASP LLM |
|---|---|---|---|---|---|---|
| The agent cannot move money; it proposes | Tool design: no ERP command tool | Tool list; every scenario | Authorization | Manage | Use of AI systems | LLM06 Excessive Agency |
| Each decision goes to the role the policy names | OPA routing; the workbench checks the role | `PolicyRoutingTest`, `ap_test.rego` | Authorization and approval | Govern, Manage | Use of AI systems | LLM06 |
| The ERP checks the decider's authority again, with the decider's own token | ERP authority matrix | `AuthorityMatrixTest` | Segregation of duties; authorization limits | Manage | Use of AI systems | LLM06 |
| A bank change needs a call-back and a second person | ERP vendor master | `BankChangeVerificationTest`; `bank-change-fraud` | Segregation of duties; fraud prevention | Manage | Use of AI systems | LLM06 |
| Nothing pays a vendor with an unverified bank change, or a repeated invoice number | OPA and the ERP | `bank-change-fraud`, `injected-invoice` | Fraud prevention | Manage | Use of AI systems | LLM01 Prompt Injection |
| A tool the policy does not name is refused | OPA allowlist | `ap_test.rego` | IT general controls: change management | Govern | AI system life cycle | LLM06 |
| Mail never reaches the agent; a model with no tools reads it into a typed reading | Occlude quarantine; the reader | `QuarantineDeclarationsTest`, `ReaderWiredTest`; the injection scenarios | Information integrity | Measure, Manage | Data for AI systems | LLM01 |
| A case whose mail tried to give instructions cannot move money | OPA (`instructionsSeen`) | `injected-reply` | Information integrity | Manage | Data for AI systems | LLM01 |
| A vendor's claim becomes a fact only when the ERP confirms it | Occlude's one endorsing gate | `vendor-names-the-po` | Information integrity | Measure | Data for AI systems | LLM01, LLM09 Misinformation |
| A proposal may cite only ids that a tool returned | OPA (`ungroundedCitations`) | `GroundingGateTest` | Supporting documentation | Measure | AI system life cycle | LLM09 |
| Vendor-written text reaches the agent only shaped like a reference | `VendorReference`, `CaseInputRenderer` | `VendorReferenceTest`, `CaseInputRendererTest` | Information integrity | Manage | Data for AI systems | LLM01, LLM05 Improper Output Handling |
| Decidable cases are settled by rules, never by a model | DMN decision tables | `ResolverTest`; the determinism check | Consistent application of policy | Map, Manage | AI system life cycle | LLM09 |
| The rules' proposals go through the same policy and deciders | `ResolverDesk` | `ResolverDeskTest` | Authorization | Manage | Use of AI systems | — |
| Every proposal records what produced it | Provenance | `DecisionFlowTest`, `ResolverDeskTest` | Audit trail | Govern, Measure | AI system life cycle; information for interested parties | — |
| Every read and refusal of quarantined mail is recorded, signed and chained | Occlude's record | `RefusalLogTest` | Audit trail | Govern | Data for AI systems | LLM02 Sensitive Information Disclosure |
| Stored agent history is encrypted | Nessy's storage codec (AES-256-GCM) | `ReaderWiredTest` | IT general controls: access | Manage | Data for AI systems | LLM02 |
| People can pause the agents at runtime | `GuardedAgents`; the workbench and `/api/oversight` | `OversightTest` | Monitoring activities | Manage | Use of AI systems | LLM06 |
| Each case's agent has a budget | `GuardedAgents`, `AgentBudget` | `OversightTest` | Monitoring activities | Manage | Use of AI systems | LLM10 Unbounded Consumption |
| At most 3 letters to one recipient on a case | `MailTools` | `MailToolsTest` | — | Manage | Use of AI systems | LLM10 |
| A case left with nobody acting goes to a person | `NeedsPerson`; the rules' sweep | `NeedsPersonTest`, `ResolverDeskTest` | Monitoring activities | Manage | Use of AI systems | — |
| The whole desk is measured before a change ships | `ap-eval`: 29 scenarios, attacks, confidence intervals | [The results](evaluation/results.md) | IT general controls: change management | Measure | AI system life cycle | LLM01, LLM09 |

## A decision's provenance

Every proposal records what produced it:

| Field | What it names |
|---|---|
| `proposer` | `rules` or `agent` |
| `agentModel` | The agent's model. None for a proposal from the rules. |
| `readerModel` | The model that reads vendor replies |
| `deskBuild` | The desk's git commit |
| `playbook` | The agent's playbook |
| `rules` | The decision tables |
| `policy` | The routing policy, as OPA held it when the proposal was routed |

Each document is named by the first 12 hex digits of its SHA-256. The case view, the auditor's
trail (`/api/cases/{id}/trail`) and the workbench show the provenance with each proposal. To
answer "why did the desk do this?", compare these values with the versions in the repository's
history.

## People's oversight while the agents run

Every input for a case agent passes one door, `GuardedAgents`. There, two controls apply.

- **Pause the agents.** A controller pauses the agents on the workbench, or with
  `POST /api/oversight/agents/pause`. The switch is stored, so it survives a restart, and every
  change is recorded with who made it and when. While the agents are paused, the rules keep
  working. Anything an agent would be told is held, and its case goes to a person. When a
  controller resumes the agents (`POST /api/oversight/agents/resume`), each held input goes to
  its agent, oldest first.
- **A budget for each case.** A case's agent may use 12 turns or 200,000 input tokens, whichever
  comes first (`ap.agents.budget.turns`, `ap.agents.budget.input-tokens`). Past that, the agent
  is told nothing more, and the case goes to a person. In the full runs, the busiest scenario used
  about 50,000 input tokens for each case.

## Metrics, and the alerts to set

The desk publishes these counts through Micrometer and the actuator (`/actuator/metrics`):

| Metric | What it counts |
|---|---|
| `ap.proposals{proposer, action}` | Proposals that reached a person |
| `ap.cases.settled{by, status}` | Cases resolved or put on hold, by the rules or by the agent |
| `ap.rules.escalated{why}` | Cases the rules gave to an agent, and why |
| `ap.occlude.refusals{reason}` | Operations the quarantine refused |
| `ap.agents.held{reason}` | Inputs held by a pause or by a spent budget |
| `ap.agents.paused` | 1 while the agents are paused |

Suggested alerts. The thresholds are starting points, set from the full runs:

| Alert | Condition | Why |
|---|---|---|
| The agents stay paused | `ap.agents.paused` is 1 for more than an hour | A pause that someone forgot stops every case that needs judgment. |
| The rules fail | `ap.rules.escalated{why="failed"}` or `{why="unread"}` rises | The ERP or the desk is failing. Cases are going to the agents for the wrong reason. |
| The agent's share grows | Escalations divided by proposals goes above about 50% (the full runs: about 35%) | New kinds of exception, or a change in the ERP's data, are reaching the agents. |
| A real refusal | `ap.occlude.refusals` with any reason except `DECLINED` | A person tried to read mail they may not, or a derivation failed. |
| Budgets are spent | `ap.agents.held{reason="budget"}` above zero | An agent loops, or a model changed. Look at the case. |
| The agent parks more | Agent-settled cases that end `ON_HOLD` rise compared with resolved ones | The agent decides less, and people do more. |

Usage for each case, by model, is at `/api/cases/{id}/usage`.

## What is still missing

- **Retention.** Stored agent history never expires, and Occlude's erasure cannot reach the
  reader's copy (Nessy finding F13).
- **The model provider.** Vendor data goes to a hosted model. A deployment needs the provider's
  data-processing terms and a decision on retention.
- **The breadth of the attacks.** The evaluation has six attacks written by hand. Current practice
  adds attacks that a model generates and adapts, attackers over several turns, and attachments,
  which the desk does not read yet.
- **The quality of human oversight.** The evaluation approves what it is asked to approve.
  Nothing measures whether a person looks at the evidence before approving.
- **The evaluation as a release gate.** The full evaluation needs a model and costs money, so CI
  does not run it. A deployment runs it before each release and keeps the report.

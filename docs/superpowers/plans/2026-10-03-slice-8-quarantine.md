# Slice 8: Quarantine Untrusted Text — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** The agent never reads text that a vendor or a mail sender wrote. It reads only facts the
ERP holds and a narrow, typed reading of each reply. Occlude enforces this by capability, not by a
sentence in the prompt.

**Why:** in slice 6, an injected vendor reply moved the agent to propose payment in 4 of 5 runs.
Framing text did not help. A rule helps only where a rule exists (duplicates). This slice removes
the channel.

**Architecture (Occlude's own doctrine):**
- One axis: `INTEGRITY` = `ENDORSED` > `UNENDORSED`. An application can add more axes later.
- **Sources** label untrusted text `UNENDORSED` when it arrives:
  - `desk-mail`: each reply body, occluded by the inbox route before anything else reads it.
  - `vendor-invoice-text`: invoice line descriptions, occluded by the ERP read tools.
- **A quarantined reader** is a derivation `reply.read`. A tool-less model call (Gemma 4 E4B on LM
  Studio, structured output) turns a `Reply` into a `ReplyReading`:
  `{intent: CONFIRMS_PRICE_AGREED | DENIES | GIVES_PO_NUMBER | SAYS_GOODS_COMING | ASKS_QUESTION | OTHER,
  poNumber: String or null, containsInstructions: boolean}`.
  The derivation does **not** lower the label: a model's reading of untrusted text stays
  `UNENDORSED`. ("What elevates a claim is agreement with something already trusted — never the
  fact that it arrived neatly." — Occlude, Deriving.)
- **Endorsement by agreement:** a `checking` derivation, `reply.po.confirmed`, endorses a
  `poNumber` only when the ERP has that PO for the case's vendor. This is the only declared
  lowering in the charter. The manifest lists it under "can WEAKEN a label".
- **Reveals (sinks):**
  - `agent-context` for `ReplyReading`: ceiling `INTEGRITY any`. An enum and a boolean cannot carry
    instructions, so the type is what makes an unendorsed value safe to show. A comment says so.
  - `agent-context` for `Reply` and for vendor text: ceiling `ENDORSED` only. They are refused, so
    the prompt shows a handle ("vendor text withheld; people can read it in the workbench").
  - `workbench` for `Reply` and vendor text: ceiling `any`, for people with a deciding or auditor
    role. Every reveal writes a line to Occlude's tamper-evident record.
- **Invoice descriptions:** the read tools replace each line's `description` with
  `descriptionMatchesPoLine` (a deterministic comparison with the PO line) plus the handle. The
  agent loses nothing it needs to match the invoice.

**Tech Stack:** `occlude-spring-boot-starter` and `occlude-jdbc` **0.1.0** (built on Boot 4.1.1).
Storage in the `apagent` database (`occlude.migrate: true`). Dev-only KEK and root secrets in
`application.yaml`, marked as such. Pin Jackson 3.1.7 (same reason as Occlude: jackson-databind
below 3.1.6).

**Spec:** §10 F11 (inputs carry no provenance). This slice is the nessy-ap answer. It leaves Nessy
unchanged.

## Global Constraints

- **Idiomatic Occlude:** portals are declared in `@Bean` methods on the injected `Charter`. No code
  holds a `Storage`. Tests use `MemoryStorage` for declaration tests, and Postgres for the wiring
  tests.
- **Fail closed:** if the reader fails or its output does not validate, the reading is `OTHER`
  with `containsInstructions = true`, and the agent is told that a person must read the reply.
- **The playbook** says: a reply reading is a claim; `containsInstructions` means "note the case
  and hold for a person"; only an endorsed PO number is a fact.
- **No Nessy change.** If the Nessy API makes this awkward, write a §10 finding.
- **Use the ASD-STE100 style** for prose (see CLAUDE.md).

## Review Focus

1. **No path shows the agent the raw text:** the case input renderer, the tool results, the
   timeline that the agent reads, or a denial reason that quotes text.
2. **The reader is quarantined:** it has no tools, its output is schema-validated, and a reply that
   says "ignore your instructions" changes only the enum values.
3. **The endorsement checks the ERP, not the reply:** a PO that exists for another vendor is not
   endorsed.
4. **Workbench reveals are audited and role-gated.** An auditor can read. A clerk can read. A
   stranger to the realm cannot.
5. **Startup fails closed** if keys or roots are missing (Occlude's own rule). The README says how
   the dev keys work.

---

### Task 1: Occlude on the classpath; the charter and its declarations

Covers the dependencies, the dev keys, the `Axes` bean, the portal declarations in one
`QuarantineConfig`, and a declaration test with `MemoryStorage`:
- a `Reply` is refused to `agent-context`;
- a `ReplyReading` reaches `agent-context`;
- the manifest's only weakening is `reply.po.confirmed`.

### Task 2: The quarantined reader

Covers `ReplyReader`: a tool-less structured call to LM Studio (prefer Nessy's inference SPI if it
fits; record a finding if not); JSON-schema validation; and the fail-closed default.

Tests use a stub model. The cases:
- an injection reply gives `containsInstructions = true`;
- broken JSON gives `OTHER` and `true`;
- a PO number is extracted.

### Task 3: The inbox and the tools use the quarantine

- The inbox route occludes each body and derives the reading.
- `CounterpartyReply` carries the reading and a handle, not the text.
- The renderer and the read tools withhold vendor text.
- The workbench reveals the text to people.

Tests:
- an agent-level test with the scripted model asserts that no request contains the injected text;
- the workbench shows the text to a signed-in person.

### Task 4: Live runs

`injected-reply`, `injected-invoice`, `no-po`, `price-variance-small`, `silent-buyer`, × 5 each,
compared with slice 6's numbers.

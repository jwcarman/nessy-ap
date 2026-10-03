# Assessing Occlude for this use case

Nessy AP uses Occlude to hold the mail that vendors send to the desk. This page is a
critique in the same style as [Assessing Nessy](nessy-assessment.md): how easy Occlude was to
use, how far it reached into the application, how much code it needed, and where it failed us.
The measurements are from slice 8 on 2026-10-03, with Occlude 0.1.0; the findings section is
updated for 0.2.0. Each finding was checked
against Occlude's source and its documentation.

## The verdict

Occlude fits this job. The desk needed a hard line between text that nobody vouched for and the
agent that acts on cases, and Occlude draws that line with labels, a short list of named gates
and a record of every crossing. Its doctrine changed our design for the better. The costs are
small: one package of code, keys at startup, and a model of "who is asking" that is flatter than
an application's roles.

Occlude can protect only the copies it holds. The largest gap we found is not in Occlude: the
quarantined reader writes the text it reads into Nessy's history, outside the lattice.

## How much code, and whose

| File | Code lines | What it does |
|---|---|---|
| `QuarantineAxes` | 12 | One axis, integrity, with two levels: endorsed and unendorsed. |
| `Untrusted` | 23 | The three types Occlude holds: a reply, a reading of it, a confirmed PO. |
| `QuarantinePortals` | 52 | Every gate, declared in one place: one source, four reveals, two derivations. |
| `Quarantine` | 49 | The facade the rest of the desk calls: receive, hold, read as a person. |
| `QuarantineConfig` | 81 | Spring wiring. About 35 lines of it build the model reader, not Occlude. |

"Code lines" excludes blank lines, comments, imports and package lines. The application uses 15
Occlude types. The mail route, the unmatched-mail list and the workbench call `Quarantine` and
import nothing from Occlude.

## How invasive it is

- **Occlude stays in one package.** No other class imports it. The agent, the policy and the ERP
  client know nothing about it.
- **It needs keys and a root at startup, and refuses to start without them.** This is correct for
  production. For development, `scripts/dev-secrets.sh` writes random values to a `.env` file
  that git ignores, and the tests generate their own.
- **It has its own tables in the desk's database, and it never joins the desk's transactions.**
  This is deliberate (see [its limits](https://jwcarman.github.io/occlude/limits/)): the record
  is evidence, and evidence must not roll back with the caller. The cost is an orphan value when
  the mail route rolls back. The value is encrypted and nothing refers to it.
- **It pins Jackson ahead of Spring Boot.** Occlude builds on Jackson 3.1.7 for three CVEs in
  earlier versions, and Boot 4.1.1 manages 3.1.5. Occlude's BOM carries the pin; import it before
  Boot's own and the application needs no pin of its own.

## What was easy

- **The doctrine corrected the design.** The first design let the model's reading of a reply
  raise its trust. Occlude's rule is to raise a claim only by agreement with something already
  trusted. So a reading stays unendorsed, and the one place trust goes up is a PO number that
  the ERP holds for the case's vendor.
- **It fails closed.** `Ceiling.nothing()` admits only an empty label, so a reveal for a person
  who is not signed in returns nothing. Declarations freeze when the charter binds, and a test
  that declared a gate late failed at once.
- **The one lowering is visible.** At startup Occlude prints a manifest of every gate. The PO
  check is marked `WEAKENS LABELS`, so a reviewer finds the only place trust goes up without
  reading the code.
- **Results are sealed types, not exceptions.** `Revealed.Allowed` or `Denied`, `Derived.Made`
  or `Refused`. Each call site handles both, and the compiler checks it.
- **Tests need no Spring.** The gates are static declaration methods. A test binds them to
  in-memory storage and checks the real ceilings in milliseconds.
- **The record found a bug that nothing else showed.** In the first live run, every reading
  failed and the desk's log was silent. Occlude's record showed `DERIVE reply.read REFUSED` for
  each reply. The cause was in Nessy (finding F14).

## How it helped the evaluation

- **Its record found the bug the evaluation could not see.** In the first live run every
  reading failed, and the safe fallback made the results look good. Occlude's record of
  refusals (`DERIVE reply.read REFUSED`) was the only evidence, and it pointed straight at the
  failing step.
- **It made "the agent never saw the text" structural, not statistical.** The agent's gates
  admit only the typed reading and a confirmed PO, never the reply. So the injection scenarios
  measure what a poisoned reading can do, not whether the model resisted the text. A test of
  the gates, with no model at all, proves the boundary.
- **Typed values made the agent's inputs checkable.** The reading has a fixed shape (intent,
  offers, a price, a PO number of the ERP's shape, an instructions flag), so a scenario can say
  exactly what the agent was told, and a failure can be traced to the reading or to the agent.
- **Its testing guide made the boundary cheap to test.** Static gate declarations and an
  in-memory store let a plain unit test check the real ceilings in milliseconds.

## Where it fought us, and what 0.2.0 changed

The desk was built on Occlude 0.1.0, and these findings went to Occlude's author. Occlude 0.2.0
answered three of them; the desk now uses 0.2.0.

| Finding on 0.1.0 | In 0.2.0 |
|---|---|
| **A crash and a decline had the same reason.** When a derivation's function threw, the refusal's reason was `DECLINED`, the same as when it returned nothing; a query that threw answered `NOT_AVAILABLE_HERE`, which looks like a policy outcome. Only the detail said "failed while reading the value", and the record encrypts the detail. Our first live reader failure was silent until we read the record. | A function or a query that throws now gets its own reason, `FAILED`. |
| **The application had to log refusals itself.** Occlude recorded each refusal but wrote nothing to the application log. | Every refusal is published as a Spring application event, `RefusalEvent`: the operation, the gate, the reason and the value's id, never the value. The desk logs each one as a WARN from a plain `@EventListener` (`RefusalLog`). It must not be a transactional listener: a refusal inside a transaction that rolls back is still in Occlude's record, so it must still reach the log. |
| **Jackson had to be pinned by the application.** Occlude needs Jackson 3.1.7 for three CVEs, and Spring Boot 4.1.1 manages 3.1.5. | Occlude's BOM pins 3.1.7. Imported before Boot's own, it wins even under `spring-boot-starter-parent`; the desk dropped its own pin, and the resolved version is still 3.1.7. |
| **"Who is asking" holds one value for each key.** A person can have several roles. | Unchanged, on purpose: the record signs the access context on every line, so a change would alter the stored format. Occlude's Spring guide now recommends what the desk does: compute one capability (here `works-cases`) from the roles, and give the ceilings that. |

## Where Occlude cannot help

Occlude's [limits page](https://jwcarman.github.io/occlude/limits/) says that a function handed
plaintext to compute with has leaked it if it also writes it somewhere. The quarantined reader is
such a function. It is a Nessy direct harness, and Nessy stores each turn's input, so the text of
every reply is also in Nessy's history. We keep that history on purpose, because an auditor must
be able to see what the reader was shown.

The desk encrypts all of Nessy's storage with the same envelope encryption that Occlude uses
(codec-crypto, AES-256-GCM), through Nessy's `StorageCodecConfigurer`. It uses a key of its own,
so each store can be rotated or revoked alone. Two gaps stay: Occlude's erasure cannot reach the
copy in Nessy's history, and nothing expires it (Nessy finding F13).

## What would make Occlude a better fit

Nothing this application needs is open. The one finding left, several values for one key, has a
documented pattern that works.

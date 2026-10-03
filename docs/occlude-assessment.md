# Assessing Occlude for this use case

Nessy AP uses Occlude to hold the mail that vendors and buyers send to the desk. This page is a
critique in the same style as [Assessing Nessy](nessy-assessment.md): how easy Occlude was to
use, how far it reached into the application, how much code it needed, and where it failed us.
The measurements are from slice 8 on 2026-10-03, with Occlude 0.1.0. Each finding was checked
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
  earlier versions. Boot 4.1.1 manages 3.1.5, and Occlude's BOM does not carry the pin, so the
  application sets `jackson-bom.version` itself.

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

## Where it fought us

| Finding | What it cost us |
|---|---|
| **"Who is asking" holds one value for each key.** | A person can have several roles. The desk computes one flag, `works-cases`, from the roles and gives the ceilings only that flag. |
| **A crash and a decline have the same reason.** | When the derivation's function throws, the refusal's reason is `DECLINED`, the same as when it returns nothing. Only the detail says "failed while reading the value", and the record encrypts the detail. The desk logged neither, so the failure was silent until we read the record. |
| **The application must log refusals itself.** | Occlude records each refusal but writes nothing to the application log. An operator who watches logs sees nothing. |

## Where Occlude cannot help

Occlude's [limits page](https://jwcarman.github.io/occlude/limits/) says that a function handed
plaintext to compute with has leaked it if it also writes it somewhere. The quarantined reader is
such a function. It is a Nessy direct harness, and Nessy stores each turn's input, so the text of
every reply is also in Nessy's `nessy_payload` table, in plaintext. Occlude's erasure cannot reach
that copy, and nothing expires it (Nessy finding F13). We store the reader's history on purpose,
because an auditor must be able to see what the reader was shown. The open question is how that
history is protected, not whether it is kept.

## What would make Occlude a better fit

In order of value to this application:
1. **A separate reason for a function that threw**, so a caller can tell a decline from a fault
   without reading the record.
2. **Multi-valued attributes in the access context**, so a ceiling can ask "has the role
   `ap-clerk`" of a person with several roles.
3. **The Jackson pin in the BOM**, so an application gets the fixed version with Occlude.

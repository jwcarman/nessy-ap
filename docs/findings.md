# Findings for Nessy

Nessy AP uses only Nessy's public API. Where that API was awkward or missing, the gap is written
down here instead of worked around in silence. The full text is in section 10 of the design spec.
Each finding was checked against Nessy's source on 2026-10-03.

| Finding | Status |
|---|---|
| **F1.** A tool cannot see its own approval. | Open |
| **F2.** An approval has no typed principal. | Open. A deliberate choice in Nessy (facts are untyped by design); fair to discuss. |
| **F3.** No read API for an agent's history from outside the engine. | Partly. The direct door's `ask` returns `TurnStats` with a real token total. Missing: a per-model, per-kind usage breakdown, and any per-turn usage on the queued door. |
| **F4.** A decision that arrives after its approval expired has no channel. | Open. A deliberate choice in Nessy (`NotAwaiting` does not tell expired from answered); fair to discuss. |
| **F5.** Nessy publishes no scripted model for tests. | Open |
| **F6.** Narration cannot be joined to a tool call. | Fixed on Nessy `main` (`ActionsRequested` carries each call's id, tool and action). Not released yet. |
| **F7.** `ApprovalRequest.callKey()` is unique only within one agent. | Open |
| **F8.** The queued dispatcher could stop for good. | Fixed on Nessy `main`. Not released yet. |
| **F9.** A person cannot reach an agent whose proposal waits for a decision. | Open |
| **F10.** `Turn.tokens` was always 0. | Fixed on Nessy `main`: the field is removed. Usage is reported as a whole, never as a token count. |
| **F11.** Inputs carry no provenance. | Open. Nessy AP's quarantine slice explores it with Occlude. |
| **F12.** Nothing checks that the policy knows a gated tool. | Open. Nessy AP's policy denies any tool it does not name. |
| **F13.** Stored agent history has no retention or cleanup. | Open. The quarantined reader stores every read, untrusted text included, with no way to expire it. |
| **F14.** The direct door cannot run inside a caller's transaction, and nothing says so. | Open. The fail-fast check and the documented precondition are missing. The desk now suspends its transaction for the read. |

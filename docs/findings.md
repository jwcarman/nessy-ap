# Findings for Nessy

Nessy AP uses only Nessy's public API. Where that API was awkward or missing, the gap is written
down here instead of worked around in silence. The full text is in section 10 of the design spec.
Each finding was checked against Nessy's source on 2026-10-03, and its status against the released
Nessy 0.5.0 on 2026-10-06.

| Finding | Status |
|---|---|
| **F1.** A tool cannot see its own approval. | Answered by the idempotency key: an approval and the call it lets run share one key, so the tool reads the desk's record of the decision by it. |
| **F2.** An approval has no typed principal. | Open. A deliberate choice in Nessy (facts are untyped by design); fair to discuss. |
| **F3.** No read API for an agent's history from outside the engine. | Fixed: `AgentStories` replays an agent's story and reads its tool results; `AgentWork` says whether an agent is idle, working or waiting on a person. The desk no longer imports an engine type. |
| **F4.** A decision that arrives after its approval expired has no channel. | Partly fixed: a call is answered by its agent and key, and an answer that comes too late is `Ignored`. Nessy reports no reason; the agent's story has it. The desk keeps its branch for a decision the ERP carried out after its call stopped waiting. |
| **F5.** Nessy publishes no scripted model for tests. | Open |
| **F6.** Narration cannot be joined to a tool call. | Fixed in 0.4.0 (`ActionsRequested` carries each call's id, tool and action). |
| **F7.** `ApprovalRequest.callKey()` is unique only within one agent. | Fixed in 0.4.0: an `IdempotencyKey` for each call, stable, and shared by its approval and its run. |
| **F8.** The queued dispatcher could stop for good. | Fixed in 0.4.0. |
| **F9.** A person cannot reach an agent whose proposal waits for a decision. | Open |
| **F10.** `Turn.tokens` was always 0. | Fixed in 0.4.0: the field is removed. Usage is reported as a whole, never as a token count. |
| **F11.** Inputs carry no provenance. | Open. Nessy AP's quarantine slice explores it with Occlude. |
| **F12.** Nothing checks that the policy knows a gated tool. | Open. Nessy AP's policy denies any tool it does not name. |
| **F13.** Stored agent history has no retention or cleanup. | Open. The quarantined reader stores every read, untrusted text included, with no way to expire it. |
| **F14.** The direct door fails inside a caller's transaction, and nothing anticipated it. | Fixed in 0.4.0: the direct harness refuses to be called inside an active transaction, with a clear error. |
| **F15.** A dropped connection to the model ends the turn, and nothing retries it. | Fixed in 0.4.0: an `Unknown` model failure reaches the retry policy. |

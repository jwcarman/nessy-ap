# Findings for Nessy

Nessy AP uses only Nessy's public API. Where that API was awkward or missing, the gap is written
down here instead of worked around in silence. The full text is in section 10 of the design spec.

| Finding | Status |
|---|---|
| **F1.** A tool cannot see its own approval. | Open |
| **F2.** An approval has no typed principal. | Open |
| **F3.** There is no read API for an agent's story from outside the process. | Open |
| **F4.** A decision that arrives after its approval expired has no channel. | Open |
| **F5.** Nessy publishes no scripted model for tests. | Open |
| **F6.** Narration cannot be joined to a tool call. | Fix written, not merged |
| **F7.** `ApprovalRequest.callKey()` is unique only within one agent. | Open |
| **F8.** The queued dispatcher could stop for good. | Fixed on a Nessy branch, not merged |
| **F9.** A person cannot reach an agent whose proposal waits for a decision. | Open |
| **F10.** `Turn.tokens` is always 0. | Removed on a Nessy branch. Usage is reported as a whole, never as a token count. |
| **F11.** Inputs carry no provenance. | Open. Nessy AP's quarantine slice explores it with Occlude. |
| **F12.** Nothing checks that the policy knows a gated tool. | Open. Nessy AP's policy denies any tool it does not name. |

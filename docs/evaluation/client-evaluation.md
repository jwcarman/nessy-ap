# The client evaluation

This is the evaluation a client would be asked to accept: every scenario in the
[catalogue](index.md#the-scenarios), 20 runs each, 400 cases, on the local models. Each rate
comes with a 95% interval (Wilson), because a rate from a few runs says less than it seems: five
passes out of five is consistent with a true rate anywhere from 57% to 100%.

| Setting | Value |
|---|---|
| Agent model | `qwen/qwen3-coder-30b` on LM Studio |
| Reader model | `google/gemma-4-e4b` on LM Studio |
| Runs | 20 per scenario, 20 scenarios |
| Side by side | 2 cases at a time; ERP-wide faults run alone |
| Desk | a fresh database, nothing else in flight |

The results are added to this page when the run completes.

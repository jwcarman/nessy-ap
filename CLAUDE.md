# nessy-ap rules

- Spec of record: `docs/superpowers/specs/2026-10-02-ap-exception-desk-design.md`.
  Anything awkward or missing in Nessy goes in its §10 findings log; never patch
  around Nessy silently.
- Nessy is consumed as the local `0.4.0-SNAPSHOT`. After any Nessy change, run
  `./mvnw install -DskipTests` in `~/IdeaProjects/nessy` before building here, or a
  stale jar in `~/.m2` shadows the source.
- Before any build: `./mvnw spotless:apply license:format`. Iterate with
  `./mvnw -q -pl :<artifactId> -am test`; run `./mvnw -q clean verify` once per task
  before its last commit. Check exit codes, never grep Maven output.
- Tests need Docker (Testcontainers). To run the apps: `docker compose up -d`
  (Postgres on 55432, RabbitMQ on 55672, management UI on 55673).
- Model policy: the table in `~/IdeaProjects/nessy/CLAUDE.md` applies here too.

## Tone and voice — ASD-STE100, about 80% of the way

Write prose that people read in Simplified Technical English (ASD-STE100), softened
to about 80% of the specification. This is an experiment: James wants to know if it
makes output easier to read. If a rule makes a sentence less clear, break that rule.

**Where it applies:** messages and reports to James, docs pages, READMEs, javadoc
and code comments, commit message bodies, eval reports, specs and plans.
**Where it does not apply:** code, identifiers, log output, quotations, and
measured data. Do not rewrite text that you quote.

**The rules, adapted:**
- Write short sentences. Procedural sentences: 20 words or fewer. Descriptive
  sentences: 25 words or fewer. One instruction in each procedural sentence.
- Write short paragraphs. One topic in each paragraph, six sentences or fewer.
- Use the active voice. Use the imperative for steps ("Run the gate", not "The
  gate should be run").
- Use simple tenses: present, past and future. Do not use "-ing" words as nouns or
  as the main verb.
- Use one word for one meaning, and use it every time. The approved dictionary is
  the spec's AP terms (case, exception, proposal, decision, desk, counterparty,
  resolution) and Nessy's ruled terms (agent type, harness, turn, tool, approver).
  It wins over STE's word list. Do not use a synonym for variety.
- Keep the articles ("the", "a"). Do not write telegraphic text.
- Do not use idioms, metaphors, slang or phrasal verbs when a single verb exists
  ("start", not "kick off"; "find", not "figure out").
- Use vertical lists for sequences and for more than three items.
- Put a warning or a condition before the instruction it controls ("If the build
  hangs after an interface change, install the changed module first").
- Make negatives clear and specific. Say what to do, not only what not to do.

**Never at the cost of truth:** do not cut a caveat, a measurement, a source or a
"not verified" mark to make a sentence shorter. Precision wins over brevity. When
STE has no word for a technical idea, use the correct technical term and do not
explain it with a vague one.

## Idiomatic constructs in well-trodden territory

When the work is in well-known territory (Spring Boot configuration, messaging,
mail, persistence, HTTP, security, testing), use the construct that the framework
or the community uses for that problem. Do not write a custom version.

- Before you write plumbing, find the framework's own mechanism and use it. For
  example: a Camel route with named EIPs, not a custom poller; Boot's
  `spring.threads.virtual.enabled`, not a system property set in `main()`; an
  auto-configuration with `@ConditionalOnMissingBean`, not a custom default.
- Use the names that the community uses (idempotent consumer, dead letter
  channel, transactional outbox), so that a reader who knows the pattern
  recognises it.
- Verify the idiom for the version in use. Idioms change between releases. Read
  the release's code or documentation, and do not trust an old memory.
- If you must leave the idiom, write the reason in a comment where you leave it.

Save custom design for the parts that are new: the agent, its tools, its policy
and its evaluation.

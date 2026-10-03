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

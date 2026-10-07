# nessy-ap

A proving ground for building enterprise agentic applications on
[Nessy](https://github.com/jwcarman/nessy): an accounts-payable exception desk
working against a simulated ERP. Mail from vendors is held in quarantine by
[Occlude](https://github.com/jwcarman/occlude), so the agent never reads untrusted text.
DMN decision tables (Apache KIE) settle each exception that a rule can settle; the agent gets
only the cases that no rule settles. Why: [Stay deterministic as long as you
can](docs/deterministic-first.md).

Documentation: <https://jwcarman.github.io/nessy-ap/> (sources in `docs/`; build with
`python3 -m mkdocs serve`). How it works, with diagrams and every control:
[`docs/system.md`](docs/system.md). How it was evaluated, and what the runs found:
[`docs/evaluation/`](docs/evaluation/index.md). What each library was like to use:
[Assessing Nessy](docs/nessy-assessment.md) and [Assessing Occlude](docs/occlude-assessment.md).
Design of record: `docs/superpowers/specs/2026-10-02-ap-exception-desk-design.md`.

## Run

You need **Java 25** (Nessy is compiled for it, and the build refuses an older JDK), Docker, and
a model: by default LM Studio on port 1234, or a hosted provider as
[Getting started](docs/getting-started.md#run-on-a-hosted-model) describes. The full procedure
is on that page. In short, from a fresh clone:

    ./mvnw -q clean verify                            # every module, every test; needs Docker
    ./scripts/dev-secrets.sh                          # once: writes .env, which git ignores
    docker compose up -d                              # Postgres, RabbitMQ, Keycloak, OPA, GreenMail
    java -jar erp-sim/target/erp-sim-0.1.0-SNAPSHOT.jar
    java -jar ap-agent/target/ap-agent-0.1.0-SNAPSHOT.jar

`erp-sim` listens on http://localhost:8081, and the desk's workbench is at
http://localhost:8082/workbench. Seed a case with the first `curl` below.

## Admin

`erp-sim` can be seeded with named scenarios, each a vendor, purchase order, receipts and an
invoice that raises one kind of match exception:

`clean-match`, `price-variance-small`, `price-variance-large`, `qty-over-receipt`, `no-receipt`,
`duplicate`, `possible-duplicate`, `no-po`, `unplanned-freight`, `bank-change-fraud`,
`item-substituted`, `no-po-real-order` (an invoice whose PO number the ERP does not know, for an
order it does know), and two attacks: `duplicate-injected` and `price-variance-injected-number`.
Two more seeds, `attack-duplicate` and `attack-no-po`, take the vendor's text in the request body,
for an attack written outside the catalogue. The evaluation builds its 29 scenarios on these
seeds; see [the scenarios](docs/evaluation/scenarios.md).

    curl -X POST localhost:8081/admin/scenarios/price-variance-small
    curl -X POST localhost:8081/admin/reset

Faults can be injected into any `/api/**` path, to see how a caller copes with a slow or failing
ERP. `/admin/**` is never faulted.

    curl -X PUT localhost:8081/admin/faults -H 'Content-Type: application/json' \
         -d '{"pathPattern": "/api/invoices/**", "latencyMillis": 2000, "errorRate": 0.3}'
    curl localhost:8081/admin/faults
    curl -X DELETE localhost:8081/admin/faults

Events go to the `erp.events` topic exchange; the RabbitMQ management UI is at
http://localhost:55673 (nessyap / nessyap).

## License

Apache License, Version 2.0. See [LICENSE](LICENSE).

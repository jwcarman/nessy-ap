# nessy-ap

A proving ground for building enterprise agentic applications on
[Nessy](https://github.com/jwcarman/nessy): an accounts-payable exception desk
working against a simulated ERP.

Documentation: <https://jwcarman.github.io/nessy-ap/> (sources in `docs/`; build with
`python3 -m mkdocs serve`). How it works, with diagrams and every control:
[`docs/system.md`](docs/system.md).
Design of record: `docs/superpowers/specs/2026-10-02-ap-exception-desk-design.md`.

## Run

    docker compose up -d
    ./mvnw -pl :erp-sim spring-boot:run

`erp-sim` listens on http://localhost:8081.

## Admin

`erp-sim` can be seeded with named scenarios, each a vendor, purchase order, receipts and an
invoice that raises one kind of match exception:

`clean-match`, `price-variance-small`, `price-variance-large`, `qty-over-receipt`, `no-receipt`,
`duplicate`, `no-po`, `unplanned-freight`, `bank-change-fraud`.

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

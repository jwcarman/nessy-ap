# nessy-ap

A proving ground for building enterprise agentic applications on
[Nessy](https://github.com/jwcarman/nessy): an accounts-payable exception desk
working against a simulated ERP.

Design: `docs/superpowers/specs/2026-10-02-ap-exception-desk-design.md`.

## Run

    docker compose up -d
    ./mvnw -pl :erp-sim spring-boot:run

`erp-sim` listens on http://localhost:8081.

# Getting started

## What you need

- Java 25.
- Docker, for the infrastructure and for the tests.
- [LM Studio](https://lmstudio.ai) with `qwen/qwen3-coder-30b` loaded and the local server on port
  1234.
- Nessy `0.4.0-SNAPSHOT` in your local Maven repository. Nessy AP builds against it until Nessy
  releases this version. Install it from a Nessy checkout with `./mvnw install -DskipTests`.

## Build

```bash
./mvnw -q clean verify
```

The build runs every test against real containers: Postgres, RabbitMQ, OPA and GreenMail. It needs
no model and no API key.

## Run

1. Start the infrastructure:
   ```bash
   docker compose up -d
   ```
   This starts Postgres, RabbitMQ, Keycloak, OPA and GreenMail.
2. Start the ERP simulator:
   ```bash
   java -jar erp-sim/target/erp-sim-0.1.0-SNAPSHOT.jar
   ```
3. Start the desk:
   ```bash
   java -jar ap-agent/target/ap-agent-0.1.0-SNAPSHOT.jar
   ```
4. Seed an exception:
   ```bash
   curl -X POST localhost:8081/admin/scenarios/price-variance-small
   ```
5. Open the workbench at <http://localhost:8082/workbench> and sign in.

## The people

Each password is the user name. This is a development realm.

| User | Role | May decide |
|---|---|---|
| `clara` | AP clerk | holds and credit-memo requests |
| `bob` | buyer | price variances on his own POs, up to 10,000 |
| `mark` | AP manager | any action up to 10,000 |
| `connie` | controller | any action at any amount |
| `audrey` | auditor | nothing; she can read |

## Ports

| Service | Port |
|---|---|
| ERP simulator | 8081 |
| Desk and workbench | 8082 |
| Postgres | 55432 |
| RabbitMQ (AMQP, management) | 55672, 55673 |
| Keycloak | 58080 |
| OPA | 58181 |
| GreenMail (SMTP, IMAP) | 53025, 53143 |

## Run the evaluation

```bash
java -jar ap-eval/target/ap-eval-0.1.0-SNAPSHOT.jar --repetitions=5
```

The evaluation writes its reports to `target/eval-results`. See [Evaluation](evaluation/index.md).

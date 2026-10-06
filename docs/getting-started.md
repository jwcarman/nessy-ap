# Getting started

## What you need

- Java 25.
- Docker, for the infrastructure and for the tests.
- A model. By default the desk uses [LM Studio](https://lmstudio.ai) on port 1234, with
  `qwen/qwen3-coder-30b` for the agent and `google/gemma-4-e4b` for the reader of incoming mail.
  To use a hosted model, see [Run on a hosted model](#run-on-a-hosted-model).

## Build

```bash
./mvnw -q clean verify
```

The build runs every test against real containers: Postgres, RabbitMQ, OPA and GreenMail. It needs
no model and no API key.

## Run

1. Generate your development secrets, once:
   ```bash
   ./scripts/dev-secrets.sh
   ```
   This writes random keys to `.env`, which git ignores. The desk reads `.env` at startup. No key is
   committed to the repository: without `.env` (or the same values in the environment) the desk
   refuses to start. Keep the file: the data in the databases is encrypted and signed under it.
2. Start the infrastructure:
   ```bash
   docker compose up -d
   ```
   This starts Postgres, RabbitMQ, Keycloak, OPA and GreenMail.
3. Start the ERP simulator:
   ```bash
   java -jar erp-sim/target/erp-sim-0.1.0-SNAPSHOT.jar
   ```
4. Start the desk:
   ```bash
   java -jar ap-agent/target/ap-agent-0.1.0-SNAPSHOT.jar
   ```
5. Seed an exception:
   ```bash
   curl -X POST localhost:8081/admin/scenarios/price-variance-small
   ```
6. Open the workbench at <http://localhost:8082/workbench> and sign in.

## Run on a hosted model

Set the provider and the models in the environment of the desk, and the provider's API key. For
example, to run the agent and the reader on OpenAI's `gpt-6-luna`:

```bash
export OPENAI_API_KEY=...
AP_PROVIDER=openai AP_MODEL=gpt-6-luna NESSY_PROVIDERS_OPENAI_WIRE=openai-responses \
AP_READER_PROVIDER=openai AP_READER_MODEL=gpt-6-luna \
java -jar ap-agent/target/ap-agent-0.1.0-SNAPSHOT.jar
```

A GPT-6 model calls tools only over OpenAI's Responses API, so set
`NESSY_PROVIDERS_OPENAI_WIRE=openai-responses` for it.

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

The full run that the results report (29 scenarios, 550 cases, on a hosted model):

```bash
java -jar ap-eval/target/ap-eval-0.1.0-SNAPSHOT.jar \
  --repetitions=20 --solo-repetitions=5 --parallel=16 --label=my-run
```

Start each full run on empty databases. The evaluation writes its reports to
`target/eval-results`. See [Evaluation](evaluation/index.md).

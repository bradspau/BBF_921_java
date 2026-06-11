# TMF921 Intent Management API — Java/Spring Boot

A complete reimplementation of the [BBF_921](../BBF_921) Python/FastAPI TMF921 v5.0.0 server using **Java 21 + Spring Boot 3.3 + Apache Jena 4.10**.

Key architectural difference from the Python version: Apache Jena TDB2 runs **in-process** (no remote Fuseki HTTP required). The TIO expression evaluator is a direct Java port of the Python 8-pass pipeline — no Jena rules are used as the primary evaluation path.

---

## Quick start

### Prerequisites

| Tool | Version |
|---|---|
| Java (JDK) | 21+ |
| Maven | 3.9+ |
| Docker + Compose | 24+ (optional) |

Install on Ubuntu/Debian:

```bash
sudo apt install openjdk-21-jdk maven
```

### Build and run

```bash
# Build
make build         # or: mvn package -DskipTests

# Run tests
make test          # or: mvn test

# Run with coverage report
make test-cov      # report at target/site/jacoco/index.html

# Start standalone server (port 8000)
make run           # or: mvn spring-boot:run

# Start access-domain server (port 8001, loads PON resource inventory)
make run-access    # or: mvn spring-boot:run -Dspring-boot.run.profiles=access
```

Wait for the log line:
```
Started Application in N.NNN seconds
```

### Verify

```bash
curl http://localhost:8000/health
```

Expected:
```json
{"status": "UP", "graph": "UP"}
```

---

## Docker

```bash
# Standalone profile (port 8000)
docker compose --profile standalone up --build

# Access profile (port 8001)
docker compose --profile access up --build
```

TDB2 data is persisted in named Docker volumes (`tdb2-standalone`, `tdb2-access`).
To start fresh: `docker compose down -v`.

---

## Seed sample data

```bash
make seed          # seeds against localhost:8000
```

Or manually:
```bash
bash seed_data/seed.sh                  # standalone
bash seed_data/seed.sh http://localhost:8001   # access
```

---

## API reference

| Endpoint | Methods | Description |
|---|---|---|
| `/tmf-api/intentManagement/v5/intent` | GET, POST | List and create intents |
| `/tmf-api/intentManagement/v5/intent/{id}` | GET, PATCH, DELETE | Manage a single intent |
| `/tmf-api/intentManagement/v5/intent/{id}/intentReport` | GET | List evaluation reports |
| `/tmf-api/intentManagement/v5/intent/{id}/intentReport/{rid}` | GET | Single report |
| `/tmf-api/intentManagement/v5/intent/{id}/observation` | POST | Inject metric observation |
| `/tmf-api/intentManagement/v5/intentSpecification` | GET, POST | IntentSpec CRUD |
| `/tmf-api/intentManagement/v5/intentSpecification/{id}` | GET, PATCH, DELETE | |
| `/tmf-api/intentManagement/v5/hub` | GET, POST | Register notification webhooks |
| `/tmf-api/intentManagement/v5/hub/{id}` | GET, DELETE | |
| `/health` | GET | Liveness/readiness probe |

Full OAS 3.0 specification: `docs/spec/TMF921_Intent_Management_v5.0.0_oas.yaml`

---

## Application profiles

| Profile | Port | Notes |
|---|---|---|
| `standalone` (default) | 8000 | No resource inventory; metric-only evaluation |
| `access` | 8001 | Loads `BBF_access/pon_resource_data.ttl`; enables set constructors and resource allocation |
| `aggregation` | 8000 | Multi-domain mode; TDB2 path separate from standalone |

Activate with `--spring.profiles.active=access` or env var `SPRING_PROFILES_ACTIVE=access`.

---

## Architecture

```
HTTP Request
     │
     ▼
Controller (api/)
     │
     ▼
Service (service/)          ──► NotificationService → hub webhooks
     │                                 (async)
     ▼
Repository (graph/repositories/)
     │
     ▼
Apache Jena TDB2  (embedded, in-process)
     │
     ▼   (on create/PATCH/observation)
EvaluationDispatcher (handler/)  ──► @Async → evaluationExecutor pool
     │
     ├── TurtleEvaluator.evaluate()
     │     ├── normalizeQuantityPredicates
     │     ├── normalizeSetPredicates
     │     ├── resolveMetricRefs  ← injects latest met:Observation
     │     ├── computeMathFunctions
     │     ├── computeSetConstructors  ← set:resourcesOfType etc.
     │     ├── resolveValidityChains
     │     ├── deriveGuaranteeStates
     │     └── deriveExtTypes
     │         → evalNode (recursive tree walk)  → EvaluationResult
     │
     ├── HandlerStateWriter  → TDB2 handlerState named graph (OODA working memory)
     ├── IntentReportRepository.create  → TDB2 report named graph
     ├── Flow 1: tryProbeTransition  (ProbeIntent ACKNOWLEDGED → ACTIVE/TERMINATED)
     ├── Flow 2: tryAutoActivate     (DEGRADED → ACTIVE on Fulfilled re-eval)
     ├── Flow 3: tryBestPropose      (failed bound → patch expressionValue)
     └── Flow 4: tryResourceAllocation  (pon:inUse write-back on Fulfilled)
```

Named graph URIs in TDB2 (base: `http://tmforum.org/api/v5`):

| Graph | Contents |
|---|---|
| `.../intents/{uuid}` | Intent + expression Turtle |
| `.../intents/{uuid}/handlerState` | Per-condition evaluation facts |
| `.../intents/{uuid}/observations` | Metric observation records |
| `.../reports/{uuid}` | IntentReport per evaluation cycle |
| `http://tmforum.org/api/v5/hubs` | Hub registrations |
| `http://tmforum.org/api/v5/resources` | PON resource inventory (access profile) |

---

## Demos

- [HSI_DEMO.md](docs/HSI_DEMO.md) — standalone HSI service intent walkthrough
- [Access_HSI_Demo.md](docs/Access_HSI_Demo.md) — PON resource allocation walkthrough

---

## Project structure

```
src/
├── main/java/org/tmforum/intent/
│   ├── api/               Controllers, FieldsFilter, HealthController
│   ├── config/            JenaConfig (TDB2), AsyncConfig, WebConfig
│   ├── exception/         GlobalExceptionHandler (TMF630)
│   ├── graph/             Namespaces, GraphNodes, SchemaInit
│   │   └── repositories/  IntentRepository, IntentReportRepository, …
│   ├── handler/           TurtleEvaluator, EvaluationDispatcher,
│   │                      HandlerStateWriter, ObservationStore, BestEffortLimits
│   ├── model/             EvaluationResult
│   └── service/           IntentService, StateMachine, NotificationService, …
└── test/java/org/tmforum/intent/
    ├── unit/              TurtleEvaluatorTest, StateMachineTest, IntentControllerTest, …
    └── integration/       IntentLifecycleIT, NegotiationIT, AccessDemoIT
```

---

## Development

```bash
# Lint (if using Checkstyle or SpotBugs — add to pom.xml to enable)
mvn checkstyle:check

# Clean TDB2 data directories
make clean
```

TDB2 data directories are created automatically in the project root when the server starts:
- `tdb2-data/` (standalone)
- `tdb2-access/` (access profile)
- `tdb2-aggregation/` (aggregation profile)

Add these to `.gitignore` if not already present.

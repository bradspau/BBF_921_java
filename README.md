# TMF921 Intent Management API — Java/Spring Boot

A complete reimplementation of the [BBF_921](../BBF_921) Python/FastAPI TMF921 v5.0.0 server using **Java 21 + Spring Boot 3.3 + Apache Jena 4.10**.

Key architectural difference from the Python version: Apache Jena TDB2 runs **in-process** (no remote Fuseki HTTP required). Evaluation runs in one of two **selectable engines** (`intent.evaluator`): the default Java evaluator (a port of the Python pipeline) or a Jena rule engine over the TIO ontology. Both write the same result graph and can be compared (see [Evaluation engines](#evaluation-engines)).

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
{"status": "UP", "graph": "UP", "evaluator": {"engine": "java", "ready": true}}
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
To start fresh: `docker compose --profile <name> down -v` (use the same profile you started with; without it Compose skips the service and its volume).

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
| `/tmf-api/intentManagement/v5/intent/{id}/observation` | POST | Inject metric observation: `{"metricUri", "value", "unit"?, "obtainedAt"?}` (see below) |
| `/tmf-api/intentManagement/v5/intentSpecification` | GET, POST | IntentSpec CRUD |
| `/tmf-api/intentManagement/v5/intentSpecification/{id}` | GET, PATCH, DELETE | |
| `/tmf-api/intentManagement/v5/hub` | GET, POST | Register notification webhooks |
| `/tmf-api/intentManagement/v5/hub/{id}` | GET, DELETE | |
| `/health` | GET | Liveness/readiness probe |

Full OAS 3.0 specification: `docs/spec/TMF921_Intent_Management_v5.0.0_oas.yaml`

### Metric observations and units

`POST .../intent/{id}/observation` is an extension endpoint (not part of TMF921) that records a `met:Observation`
for a metric and re-evaluates the intent. The value is a quantity (TR292D): a number with an optional `unit`.

```json
{ "metricUri": "http://broadband-forum.org/Intent#LatencyMetric", "value": 8.0, "unit": "ms" }
```

`unit` is a prefix and a unit symbol (`Mbps`, `Gbps`, `ms`, `us`, `Kibit`, `percent`, ...). Conditions compare
quantities after converting prefixes, so `0.15 Gbps` satisfies `>= 100 Mbps`. A value whose unit has a different
dimension, or no unit when the bound has one, cannot be compared: the condition is `false` with a
"unit mismatch" reason. Omit `unit` for dimensionless values, such as counts. Bare numbers compare with bounds
that have no unit.

---

## Application profiles

| Profile | Port | Notes |
|---|---|---|
| `standalone` (default) | 8000 | No resource inventory; metric-only evaluation |
| `access` | 8001 | Loads `BBF_access/pon_resource_data.ttl`; enables set constructors and resource allocation |
| `aggregation` | 8000 | Multi-domain mode; TDB2 path separate from standalone |
| `fuseki` | 3030 | Opt-in add-on: embedded Fuseki (SPARQL endpoint + web UI) over the live TDB2 dataset; combine with another profile |

Activate with `--spring.profiles.active=access` or env var `SPRING_PROFILES_ACTIVE=access`.

### Browsing the graph with Fuseki

Add the `fuseki` profile to any other profile, e.g. `mvn spring-boot:run -Dspring-boot.run.profiles=access,fuseki`, then open <http://localhost:3030> for the Fuseki UI. The dataset is `/intents`; SPARQL endpoint is `http://localhost:3030/intents/sparql`. Use `GRAPH ?g { ... }` to query the named graphs.

| Property | Default | Meaning |
|---|---|---|
| `intent.fuseki.port` | `3030` | Fuseki HTTP port |
| `intent.fuseki.dataset-name` | `intents` | Dataset path |
| `intent.fuseki.allow-update` | `false` | Read-only by default; write via the TMF921 API |
| `intent.fuseki.loopback` | `true` | Bind to localhost only; `docker-compose.yml` sets `false` inside the container |

**Docker:** `docker-compose.yml` already publishes `127.0.0.1:3030:3030` and reads the Spring profile list from `APP_PROFILES`:

```bash
APP_PROFILES=access,fuseki docker compose --profile access up --build        # port 8001 + 3030
APP_PROFILES=standalone,fuseki docker compose --profile standalone up --build  # port 8000 + 3030
```

(Docker Compose `--profile` selects the service; `APP_PROFILES` sets the Spring profiles inside it.) Without `fuseki` in `APP_PROFILES`, nothing listens on 3030.

Wait for `Start Fuseki (http=3030)` in the log. Only one Fuseki can run per host at a time (port 3030).

The UI's admin features (dataset create/delete, backup) are not available in embedded mode; query and graph browsing work.

---

## Evaluation engines

`intent.evaluator` selects who evaluates an intent. Both engines produce the same **result graph**, so everything
downstream (reports, handling state, Flows 1-4) is engine-agnostic.

| Value | Behaviour |
|---|---|
| `java` (default) | `TurtleEvaluator`: hand-written Java evaluator. Fast (about 2 ms per evaluation) and the oracle for the conformance corpus. |
| `jena` | `JenaEvaluationEngine`: chained Jena `GenericRuleReasoner` stages over the ontology, expression, observations and resources (rule files in `src/main/resources/rules/`). About 15-45 ms per evaluation. Needs the licensed ontology files; without them `/health` reports the evaluator as not ready (HTTP 503) and evaluations are skipped with an error log. |
| `compare` | Runs both, returns the Java outcome, and logs a `compare:` warning when the two result graphs differ, when the handler results (state, reason, condition entries, Flow 3 proposal) differ, or when the Jena engine fails or is not ready. Meant for development; no warnings means the engines agree. |

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--intent.evaluator=jena       # or: java | compare
INTENT_EVALUATOR=compare mvn spring-boot:run                                   # environment form
INTENT_EVALUATOR=jena APP_PROFILES=access docker compose --profile access up --build   # Docker (default java)
```

`GET /health` shows `"evaluator": {"engine": "...", "ready": true|false}`.

**Result graph.** Explicit `rdf:value true|false` on every IRI-identified intent, expectation and condition; the
resources chosen for each delivery expectation (`rdfs:member` of the target); `icm:reason` where an element is false
for a reason (no observation, unit mismatch, nothing to choose, value outside bound); the observation a metric
condition used. It is stored per intent in the named graph `.../intents/{uuid}/inferred`, replaced on every
evaluation (browse it in Fuseki). `icm:result` is not in this graph: the handler builds the TR290 reports from the
graph and the expression (`ReportBuilder`, `ResultAdapter`).

**Conformance corpus.** `src/test/resources/conformance/` holds cases (expression, observations, resources, expected
graph) that run against both engines. Where the Jena engine deliberately follows the TM Forum PDFs rather than Java, the
case has an `expected.jena.ttl` and an entry in `src/test/resources/conformance/DEVIATIONS.md` (PDF reference, what
each engine does, the decision). `ReadinessGuardTest` keeps the Jena engine's ready flag honest.

**Licensed ontology.** The TIO ontology files (`ontology/*.ttl`) are TM Forum licensed and are not in the repository.
Tests that need them skip themselves with a message when the files are absent, so a fresh checkout builds and runs
everything else; the full conformance and readiness runs happen on a machine that has them.

The `handlerState` graph (`imo:conditionPassed`, written by `HandlerStateWriter`) is kept alongside the inferred graph;
Flow 4 and notifications still read it.

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
     ├── EvaluationEngine.evaluate()   (intent.evaluator: java | jena | compare)
     │     ├── java: TurtleEvaluator (normalise, resolve metrics, set constructors,
     │     │         choice, tree walk) → ResultGraphWriter
     │     └── jena: stage A facts → choice strata → stage B (rules/tio_*.rules)
     │         → result graph  → InferredGraphStore (.../inferred named graph)
     │
     ├── ResultAdapter  → EvaluationResult (state, reason, condition list)
     ├── HandlerStateWriter  → TDB2 handlerState named graph (OODA working memory)
     ├── ReportBuilder → IntentReportRepository.create  → TDB2 report named graph (TR290 report)
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
| `.../intents/{uuid}/inferred` | Engine result graph (rdf:value, chosen members, reasons), replaced per evaluation |
| `.../intents/{uuid}/observations` | Metric observation records |
| `.../reports/{uuid}` | IntentReport per evaluation cycle |
| `http://tmforum.org/api/v5/hubs` | Hub registrations |
| `http://tmforum.org/api/v5/resources` | PON resource inventory (access profile) |

> **Note:** These are project-local graph names, not identifiers defined by the TMF921 specification. They are never dereferenced over HTTP and are kept identical to the BBF_921 Python project so the demo Turtle files work unchanged. The REST endpoints themselves follow the spec: `{apiRoot}/tmf-api/intentManagement/v5/...`.

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
│   ├── engine/            EvaluationEngine seam, EngineConfig, Java/Jena/Comparing engines,
│   │                      ResultContract, ResultGraphWriter, InferredGraphStore, ResultAdapter
│   ├── handler/           TurtleEvaluator, JenaBuiltins, Quantities, ReportBuilder,
│   │                      EvaluationDispatcher, HandlerStateWriter, ObservationStore, BestEffortLimits
│   ├── model/             EvaluationResult
│   └── service/           IntentService, StateMachine, NotificationService, …
└── test/java/org/tmforum/intent/
    ├── unit/              TurtleEvaluatorTest, Jena*RulesTest, StateMachineTest, IntentControllerTest, …
    ├── conformance/       ConformanceTest, ReadinessGuardTest (corpus in src/test/resources/conformance)
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

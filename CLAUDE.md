# TMF921 Intent Management API v5.0.0 — Java/Jena Edition

**Stack:** Java 21 + Spring Boot 3.3 + Apache Jena 4.10 (TDB2 embedded store, GenericRuleReasoner, ARQ SPARQL).
TIO quantity reasoning runs **in-process** via Jena's `GenericRuleReasoner` + `tio_all.rules` — no Fuseki HTTP required.
**Models:** Hand-written Jackson POJOs in `src/main/java/org/tmforum/intent/model/`
**OAS:** `docs/spec/TMF921_Intent_Management_v5.0.0_oas.yaml`
**Repo:** BBF_921_java (separate from BBF_921 Python project)

## Commands
- Build: `./mvnw package -DskipTests`
- Run tests: `./mvnw test`
- Coverage report: `./mvnw test jacoco:report` (must stay ≥80%)
- Run dev server (standalone): `./mvnw spring-boot:run`
- Run dev server (access profile): `./mvnw spring-boot:run -Dspring-boot.run.profiles=access`
- Seed data: `bash seed_data/seed.sh`

## Architecture

### Evaluation (replaces Python evaluator.py)
For each evaluation cycle, `TurtleEvaluator` creates an ephemeral `InfModel`:
1. Load expression Turtle + observations + optional resource data into a base `Model`
2. Wrap with `GenericRuleReasoner` loaded from `src/main/resources/rules/tio_all.rules`
3. Call `infModel.prepare()` — fires all TIO rules, materialises inferred triples
4. Run SPARQL via ARQ on the inferred model to read `imo:intentHandlingState` + per-condition results
5. Map to `EvaluationResult{intentHandlingState, reason, conditions[]}`

Custom Jena `Builtin` implementations (in `JenaBuiltins.java`) handle:
- `set:setforAll` — dynamic variable substitution (no Jena rule equivalent)
- `mf:mflogistic`, `mf:mfpoly`, `mf:mfmapping` — floating-point math
- `quan:mean`, `quan:median`, `quan:sumOfSet` — list aggregates

### Persistent store
TDB2 `Dataset` bean (from `JenaConfig`) — direct in-process access, no HTTP to Fuseki.
All SPARQL queries and updates use ARQ `QueryExecutionFactory` on the TDB2 Dataset.

### Async dispatch
`EvaluationDispatcher` runs evaluation as a Spring `@Async` task (ThreadPoolTaskExecutor).
Same 4 post-evaluation flows as BBF_921 Python:
- Flow 1: ProbeIntent auto-transition (ACKNOWLEDGED→ACTIVE|TERMINATED)
- Flow 2: Judge/Preference auto-activation (DEGRADED→ACTIVE when Fulfilled)
- Flow 3: Best-effort proposal (PATCH expression with observed bounds)
- Flow 4: Resource allocation write-back (pon:inUse, pon:assignedToService)

## Layer Map

| Layer | Package | Key classes |
|-------|---------|-------------|
| Config | `config` | `JenaConfig`, `AsyncConfig`, `WebConfig` |
| Graph | `graph` | `Namespaces`, `GraphNodes`, `SchemaInit` |
| Repositories | `graph.repositories` | `IntentRepository`, `IntentReportRepository`, etc. |
| Handler | `handler` | `TurtleEvaluator`, `JenaBuiltins`, `EvaluationDispatcher`, `HandlerStateWriter` |
| Models | `model` | `Intent`, `IntentReport`, `EvaluationResult`, etc. |
| Services | `service` | `IntentService`, `StateMachine`, `NotificationService` |
| API | `api` | `IntentController`, `HubController`, etc. |

## Rules
- Read the listed docs/ file before writing code for each phase
- TDB2 Dataset access MUST be wrapped in transactions: `dataset.begin(ReadWrite.READ/WRITE)` … `dataset.end()`
- `expressionValue` stored as opaque String — only `TurtleEvaluator` materialises triples from it
- `id`, `href`, `creationDate`, `lastUpdate` set server-side — strip from POST, reject on PATCH
- `lifecycleStatus` FSM uses ALL-CAPS: `ACKNOWLEDGED`, `ACTIVE`, `FULFILLED`, `DEGRADED`, `SUSPENDED`, `TERMINATED`
- All resource ID path params validated as UUID pattern
- Tests must pass ≥80% coverage before any phase advances

## Reference Files
| File | Read for |
|---|---|
| `docs/01-resources.md` | Mandatory attributes, sub-resource fields, polymorphism |
| `docs/02-operations.md` | All endpoints, status codes, query params, pagination |
| `docs/03-expressions.md` | Turtle/JsonLd structure, TIO namespaces |
| `docs/04-state-machine.md` | lifecycleStatus FSM, valid transitions |
| `docs/05-notifications.md` | Hub, 10 eventTypes, async fan-out |
| `docs/06-negotiation.md` | ProbeIntent, JudgePreference, BestPropose flows |
| `docs/07-graph-schema.md` | Named graph URIs, SPARQL traversal patterns |
| `docs/08-patch-rules.md` | Patchable / non-patchable fields, RFC 7386 merge patch |
| `docs/09-deliverables.md` | Phase checklist |

## Skills
| Skill | Path | Use when |
|---|---|---|
| TMF921 Intent | `.claude/skills/tmf921-intent/tmf921-intent-SKILL.md` | Intent, IntentReport, expressions, lifecycle, events |
| TMF API Guidelines | `.claude/skills/tmf-api-guidelines/tmf-api-guidelines-SKILL.md` | Routers, error handling, TMF630, URI structure |
| Backend Architecture | `.claude/skills/backend-architecture-patterns/SKILL.md` | Service layering, repository pattern |
| API Service Tests | `.claude/skills/api-service-test-generator/SKILL.md` | Multi-step lifecycle flows, integration tests |
| Code Reviewer | `.claude/skills/code-reviewer/SKILL.md` | Security, performance, best-practice review |
| REST API Design | `.claude/skills/rest-api-design-guide/SKILL.md` | REST naming, URI patterns |

## Named Graph URIs (same as BBF_921 Python — ensures demo Turtle files work unchanged)
```
Intent:        http://tmforum.org/api/v5/intents/{uuid}
HandlerState:  http://tmforum.org/api/v5/intents/{uuid}/handlerState
Observations:  http://tmforum.org/api/v5/intents/{uuid}/observations
Report:        http://tmforum.org/api/v5/reports/{uuid}
Hubs:          http://tmforum.org/api/v5/hubs
Resources:     http://tmforum.org/api/v5/resources
```

## Base URLs
```
Base: {scheme}://{host}:{port}/tmf-api/intentManagement/v5
Intent:       {base}/intent/{id}
IntentReport: {base}/intent/{intentId}/intentReport/{id}
IntentSpec:   {base}/intentSpecification/{id}
```

## Demos
- **HSI_DEMO.md** — single-domain metric flow, ProbeIntent, Best/Propose (standalone profile, port 8000)
- **Access_HSI_Demo.md** — PON resource inventory, set constructors, resource allocation write-back (access profile, port 8001)

## Issue Tracking
This project uses **bd (beads)** for issue tracking.
```bash
bd ready              # Find available work
bd show <id>          # View issue details
bd update <id> --claim  # Claim work
bd close <id>         # Complete work
```

## Phase Order (do not skip ahead — confirm before advancing)
0. Project skeleton + beads setup
1. Graph + Config layer (JenaConfig, Namespaces, repositories)
2. Handler / Jena reasoning (TurtleEvaluator, JenaBuiltins, ObservationStore)
3. Service + state machine + dispatcher
4. API controllers layer
5. Test suite (≥80% coverage)
6. Infrastructure + demo validation

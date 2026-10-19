# TMF921 Intent Management API v5.0.0 — Java/Jena Edition

**Stack:** Java 21 + Spring Boot 3.3 + Apache Jena 4.10 (TDB2 embedded store, GenericRuleReasoner, ARQ SPARQL).
Evaluation runs **in-process** in one of two selectable engines (Java `TurtleEvaluator`, or Jena rules) — no Fuseki HTTP required.
**Models:** Hand-written Jackson POJOs in `src/main/java/org/tmforum/intent/model/`
**OAS:** `docs/spec/TMF921_Intent_Management_v5.0.0_oas.yaml`
**Repo:** BBF_921_java (separate from BBF_921 Python project)

## Commands
There is no `./mvnw`; use `mvn`.
- Build: `mvn package -DskipTests`
- Run tests: `mvn test` (integration: `mvn test -Dtest='*IT' -DfailIfNoTests=false`)
- Coverage report: `mvn test jacoco:report` (must stay ≥80%)
- Run dev server (standalone): `mvn spring-boot:run`
- Run dev server (access profile): `mvn spring-boot:run -Dspring-boot.run.profiles=access`
- Pick the engine: `mvn spring-boot:run -Dspring-boot.run.arguments=--intent.evaluator=jena` (`java|jena|compare`) or env `INTENT_EVALUATOR`; default `java`
- Seed data: `bash seed_data/seed.sh`

## Architecture

### Evaluation: engine seam (`engine` package)
`intent.evaluator` (`engine/EngineConfig`) selects the active `EvaluationEngine`: `java` (default,
`JavaEvaluationEngine` wrapping `TurtleEvaluator`), `jena` (`JenaEvaluationEngine`), or `compare` (`ComparingEngine`:
runs both, returns Java's outcome, logs `compare:` warnings when the result graphs or the handler projection differ).
`evaluate(EvaluationInput)` returns `EvaluationOutcome(Model resultGraph, EvaluationResult result)`. `/health` reports
`evaluator.engine` and `evaluator.ready`; evaluations are skipped (error log) if the selected engine is not ready.

**Result graph** (the shared contract, `engine/ResultContract`): explicit `rdf:value true|false` on every
IRI-identified intent, expectation and condition; chosen target members (`rdfs:member`); `icm:reason`; the observation
used by a metric condition. No `icm:result` here: that lives only in TR290 reports. It is stored per intent in the
`/inferred` named graph (`InferredGraphStore`), replaced on each evaluation.

**Handler side**: `handler/ReportBuilder` turns the result graph plus the expression into the TR290 report
(`icm:IntentReport`/`ExpectationReport`/`ConditionReport`); the handler `EvaluationResult` (state, reason, flat
condition list) that Flows 1-4 and `BestEffortLimits` consume comes from the Java evaluator itself, and for the Jena
engine from `engine/ResultAdapter`, which builds it from the rules' facts.
`HandlerStateWriter` still writes the `handlerState` graph (`imo:conditionPassed`); Flow 4 and notifications depend on it.

**Note on `expressionValue` readers**: besides `TurtleEvaluator`, the Jena engine parses it (`EvaluationInputs.parse`), the
dispatcher re-parses it for `ReportBuilder` (TR290 report structure), `ResultAdapter` reads it for entry order and
`HandlerProjection` (compare mode) reads it too; none of them evaluates anything from it other than the engines.

**Java engine**: `TurtleEvaluator` is a hand-written tree walk (normalise predicates, resolve metrics to the newest
observation, set constructors, delivery choice with held resources, `Quantities` for unit-aware comparison); it is also
the conformance oracle and writes the result graph through `ResultGraphWriter`.

**Jena engine**: per evaluation, chained `GenericRuleReasoner` stages over the ontology + expression + observations +
resources (rule files in `src/main/resources/rules/`): stage A positive facts (`tio_a_*.rules`: core, quantity, sets,
logic, delivery, reasons), choice strata (`tio_c_choose.rules`, one per level up to `MAX_CHOICE_DEPTH`), stage B
defaults, negation and roll-up (`tio_b_*.rules`: pools, logic, sets, delivery, reasons). Rule invariant: `noValue` only
over facts complete when the stage starts. Custom Jena `Builtin`s in `handler/JenaBuiltins` (`lexLess`, `unitCompare`,
`quantityText`, `allSubjectsIn`, `prevLowest`, ...) only where rules cannot express it. Needs the licensed
`ontology/*.ttl` (untracked, never `git add`); without them the engine is not ready and ontology-dependent tests skip.

**Conformance and parity**: `src/test/resources/conformance/` is the corpus (case dirs with `expected.ttl`, optional
`expected.jena.ttl`); `DEVIATIONS.md` registers every difference between the engines with the PDF reference
(`DeviationRegisterTest`/`ConformanceLoader` enforce it). `ReadinessGuardTest` guards `JenaEvaluationEngine.READY`.
Rules for a new function: add a corpus case, run both engines, register any deviation.

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
| Engine | `engine` | `EvaluationEngine`, `EngineConfig`, `JavaEvaluationEngine`, `JenaEvaluationEngine`, `ComparingEngine`, `ResultContract`, `ResultGraphWriter`, `InferredGraphStore`, `ResultAdapter` |
| Handler | `handler` | `TurtleEvaluator` (Java engine core), `JenaBuiltins`, `Quantities`, `ReportBuilder`, `EvaluationDispatcher`, `HandlerStateWriter`, `ObservationStore`, `BestEffortLimits` |
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
Inferred:      http://tmforum.org/api/v5/intents/{uuid}/inferred
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

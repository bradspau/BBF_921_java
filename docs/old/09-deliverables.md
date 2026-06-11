# Deliverables — Phase Checklist

## Pre-Phase — Infrastructure
- [ ] `rdfox/RDFox`             — RDFox binary placed here
- [ ] `rdfox/licence.lic`       — RDFox trial licence placed here
- [ ] `rdfox/server.properties` — RDFox server config (port 12110, persist to data/rdfox/)
- [ ] `ontology/allttl.ttl`     — TIO v3.6.0 full ontology
- [ ] `ontology/tio-rules.dlog` — Datalog rules for TIO function semantics
- [ ] `ontology/shacl-intent.ttl` — SHACL shapes (used for dev reference)
- [ ] `make models`             — generates src/api/schemas/generated.py

## Phase 1 — Graph Store + Schema
- [ ] `src/graph/store.py`          — RDFox process manager + SPARQL client
- [ ] `src/graph/schema_init.py`    — datastore creation + ontology + rules load
- [ ] `src/graph/namespaces.py`     — TIO namespace URIRef constants
- [ ] `src/graph/nodes.py`          — dataclass definitions for all node types
- [ ] `tests/unit/test_store.py`    — health check, SPARQL round-trip

## Phase 2 — Repository Layer
- [ ] `src/graph/repositories/intent_repository.py`
- [ ] `src/graph/repositories/intent_report_repository.py`
- [ ] `src/graph/repositories/intent_spec_repository.py`
- [ ] `src/graph/repositories/hub_repository.py`
- [ ] `tests/unit/test_repositories.py` — mock RDFox SPARQL responses

## Phase 3 — Service + State Machine
- [ ] `src/services/intent_service.py`
- [ ] `src/services/state_machine.py`    — explicit FSM, valid transition table
- [ ] `src/services/notification_service.py` — hub fan-out via httpx
- [ ] `tests/unit/test_state_machine.py` — all valid + invalid transitions

## Phase 4 — API Layer
- [ ] `src/api/routers/intent.py`
- [ ] `src/api/routers/intent_report.py`
- [ ] `src/api/routers/intent_spec.py`
- [ ] `src/api/routers/hub.py`
- [ ] `src/api/schemas/generated.py`     — from make models
- [ ] `src/api/middleware/fields_filter.py`
- [ ] `src/main.py`                      — lifespan: start_rdfox → initialise_schema
- [ ] `tests/unit/test_schemas.py`

## Phase 5 — Intent Handler
- [ ] `src/handler/evaluator.py`         — TIO expression evaluation via RDFox
- [ ] `src/handler/dispatcher.py`        — routes intents to evaluator
- [ ] `tests/unit/test_evaluator.py`

## Phase 6 — Integration + Contract Tests
- [ ] `tests/integration/test_intent_lifecycle.py`
- [ ] `tests/integration/test_negotiation.py`
- [ ] `tests/integration/test_notifications.py`
- [ ] `tests/contract/`                  — Schemathesis OAS3 conformance

## Phase 7 — Infrastructure
- [ ] `docker-compose.yml`               — FastAPI + RDFox as subprocess (single container)
- [ ] `Dockerfile`
- [ ] `seed_data/seed_intents.py`
- [ ] `README.md`
- [ ] `postman/TMF921_collection.json`

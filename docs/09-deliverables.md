# Deliverables Phase Checklist

## Phase 1 — Graph Store and Schema

- `src/graph/store.py` — Jena/Fuseki SPARQL client
- `src/graph/schema_init.py` — graph initialisation helpers
- `src/graph/namespaces.py` — namespace constants
- `src/graph/nodes.py` — node definitions
- `tests/unit/test_store.py` — health check and SPARQL round-trip

## Phase 2 — Repository Layer

- `src/graph/repositories/intent_repository.py`
- `src/graph/repositories/intent_report_repository.py`
- `src/graph/repositories/intent_spec_repository.py`
- `src/graph/repositories/hub_repository.py`
- `tests/unit/test_repositories.py`

## Phase 3 — Service and State Machine

- `src/services/intent_service.py`
- `src/services/state_machine.py`
- `src/services/notification_service.py`
- `tests/unit/test_state_machine.py`
- `tests/unit/test_intent_service.py`

## Phase 4 — API Layer

- `src/api/routers/intent.py`
- `src/api/routers/intent_report.py`
- `src/api/routers/intent_spec.py`
- `src/api/routers/hub.py`
- `src/api/middleware/fields_filter.py`
- `src/api/error_handlers.py`
- `src/main.py`
- `tests/unit/test_routers.py`

## Phase 5 — Intent Handler

- `src/handler/evaluator.py`
- `src/handler/dispatcher.py`
- `tests/unit/test_evaluator.py`

## Phase 6 — Integration and Contract Tests

- `tests/integration/conftest.py`
- `tests/integration/test_intent_lifecycle.py`
- `tests/integration/test_negotiation.py`
- `tests/integration/test_notifications.py`
- `tests/contract/schemathesis_config.py`

## Phase 7 — Infrastructure

- `docker-compose.yml`
- `Dockerfile`
- `seed_data/seed_intents.py`
- `README.md`
- `postman/TMF921_collection.json`

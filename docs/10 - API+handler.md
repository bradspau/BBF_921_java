# API ↔ Intent Handler Wiring

## Problem

Phase 4 (API layer) and Phase 5 (Intent Handler) were built independently.
Both are correct against their own specs, but no code connects them at runtime.

When `POST /intent` is called:
- The intent is persisted in Fuseki ✓
- An `intentCreateEvent` notification fires ✓
- `schedule_evaluation` is **never called** ✗

Result: `GET /intent/{id}/intentReport` always returns `[]` for any intent
created through the normal API flow.

---

## What changes

### 1. `src/services/intent_service.py`

`IntentService.__init__` gains one new parameter: `report_repo: IntentReportRepository`.
`hub_repo` is stored directly (previously only handed to `NotificationService`).

`create()` and `update()` each call `schedule_evaluation` after the repo write
succeeds. The call is fire-and-forget — it drops an asyncio background task
and returns immediately, so the HTTP response is not delayed.

### 2. `src/api/deps.py`

`get_intent_service()` constructs and passes `IntentReportRepository(client)`
to `IntentService`.

---

## Call flow after this change

```
POST /intent
  └─ IntentService.create()
       ├─ IntentRepository.create()          ← persist intent
       ├─ NotificationService.schedule()     ← intentCreateEvent
       └─ schedule_evaluation()              ← drop background task
              └─ dispatch_evaluation()       (runs asynchronously)
                   ├─ evaluator.evaluate_intent()
                   └─ IntentReportRepository.create()  ← write report
                        └─ NotificationService.schedule()  ← intentReportCreateEvent
```

`PATCH /intent/{id}` follows the same pattern: evaluation is re-triggered
after every successful update so the report reflects the current expression
and lifecycle state.

---

## Constraints preserved

- HTTP response time is unchanged — evaluation is background-only.
- Evaluation errors are logged and never surface to the API caller.
- No new files. No new concepts. Both sides were already built and tested.

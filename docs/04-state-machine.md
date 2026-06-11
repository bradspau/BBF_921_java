# Intent Lifecycle State Machine

## Valid states and transitions

- New intents initialise to `ACKNOWLEDGED`.
- Valid runtime states include `ACTIVE`, `FULFILLED`, `DEGRADED`, `SUSPENDED`, and `TERMINATED`.
- `TERMINATED` is terminal and has no exit transition.

## Transition rules

- Intent Owner may set `ACKNOWLEDGED` and `TERMINATED` where allowed by the lifecycle.
- Intent Handler may set `ACTIVE`, `FULFILLED`, `DEGRADED`, `SUSPENDED`, and `TERMINATED` where allowed by the lifecycle.
- Any invalid lifecycle transition must return HTTP 400 with TMF error body.

## Required side effects of every state change

Every valid state change must:
1. Update `tmf:lifecycleStatus` on the Intent node.
2. Set `tmf:statusChangeDate` to current UTC timestamp.
3. Update `dcterms:modified` on the Intent node.
4. Write a StateChange named graph that is write-once and never updated or deleted.
5. Fire `IntentStatusChangeEvent` asynchronously to all registered hubs after the write succeeds.

## Domain events

Relevant TMF921A domain events include:
- `IntentReceived`
- `IntentAccepted`
- `IntentRejected`
- `IntentRemoval`
- `IntentHandlingEnded`
- `StateComplies`
- `StateDegrades`
- `UpdateReceived`
- `UpdateAccepted`
- `UpdateRejected`
- `UpdateFinished`

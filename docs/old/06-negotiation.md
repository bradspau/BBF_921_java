# TMF921 Negotiation Flows (TMF921A §4.2)

## Probe Intent Flow
Used when Handler needs to propose alternative terms.

```
1. Owner   → POST /intent (@type: Intent)
2. Handler → POST /intent (@type: ProbeIntent, @baseType: Intent)
             intentRelationship[].id references original Intent id
3. Owner   → PATCH /intent/{probeId} (accept/reject via lifecycleStatus)
```

## Judge/Preference Flow
Used when Owner must approve a Handler modification.

```
1. Handler detects degradation → fires StateDegrades event → DEGRADED state
2. Owner   → PATCH /intent/{id} (new preference values in expressionValue)
3. Handler → PATCH /intent/{id} (confirm via lifecycleStatus: ACTIVE)
```

## Best/Propose Flow
Used when Handler proposes best achievable parameters.

```
1. Owner   → POST /intent
2. Handler → PATCH /intent/{id} (expressionValue updated with best-effort values)
3. Owner   → PATCH /intent/{id} (approve via lifecycleStatus: ACTIVE)
```

## ProbeIntent Rules
- Created via `POST /intent` with `@type: ProbeIntent`
- Stored as `tmf:ProbeIntent` RDF class — separate named graph, same structure as Intent
- Inherits ALL Intent mandatory attributes, operations, and notifications
- Non-patchable fields are identical to Intent
- Stores reference to parent Intent via `intentRelationship[].id` triple:
  `(probe_uri, TMF.relatesTo, parent_intent_uri)`
- All 3 flows use standard PATCH — no special endpoints

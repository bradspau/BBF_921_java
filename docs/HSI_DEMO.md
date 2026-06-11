# HSI Intent Demo — End-to-End Walkthrough (Java)

This guide exercises the BBF High-Speed Internet (HSI) service intent through the
TMF921 API, verifies that the intent handler evaluates conditions correctly, and shows
`intentHandlingState` cycling between `Fulfilled` and `Degraded` as metric observations
change.

> For the access domain demo with PON resource inventory and UNI/CTAG allocation,
> see [`Access_HSI_Demo.md`](Access_HSI_Demo.md).

---

## Architecture

```
POST /observation  →  TDB2 observations graph (in-process)
                           ↓ merged at evaluation time
POST /intent       →  TDB2 intent named graph
                           ↓
               TurtleEvaluator.evaluate()   ← Java, embedded Jena
                           ↓
               IntentReport  +  handlerState graph  (TDB2)
```

The evaluator runs as a Spring `@Async` background task every time an intent is
created, patched, or receives a new observation. It merges the expression Turtle with
the observation graph, then walks the TIO expression tree to produce `Fulfilled` or
`Degraded`.

---

## Start with a clean store

TDB2 data persists in `tdb2-data/` (or the Docker volume).

### Docker (recommended — wipes volume on restart)

```bash
docker compose down -v   # -v removes named volumes → clean TDB2 store
docker compose --profile standalone up --build
```

### Dev server (wipe TDB2 manually)

```bash
make clean    # deletes tdb2-data/, tdb2-access/, tdb2-aggregation/
make run
```

---

## Prerequisites

### Option A — Docker

```bash
docker compose --profile standalone up --build
```

Wait for:
```
Started Application in N.NNN seconds
```

### Option B — Local dev server

```bash
make build
make run
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

## The HSI intent structure

The inline expression used in this demo defines a top-level `log:allOf` over three
expectations:

| Expectation | Evaluator | What it checks |
|---|---|---|
| `DeliveryCheck` | `icm:DeliveryExpectation` | `SelectedUNIInterface` container has an `rdfs:member` typed `bbf:HSIService` |
| `UNICheck` | `log:allOf` + `log:match` | UNI has `operationalState=OperationalUp` AND `provisioningState=Ready` |
| `PerformanceCheck` | `log:allOf` + 5× quantity conditions | DL ≥ 100 Mbps, UL ≥ 20 Mbps, latency < 25 ms, jitter < 3 ms, packet loss < 0.1 % |

**Structural conditions** (DeliveryExpectation, log:match UNI state) depend on facts
asserted inside the expression Turtle — they cannot be satisfied by metric observations.
The demo expression includes these inline so every condition is evaluable from the start.

**Metric conditions** (the five quantity conditions) require `met:Observation` records
posted via `POST /intent/{id}/observation`.

---

## Step 1 — Create the HSI intent

```bash
HSI_INTENT=$(curl -s -X POST http://localhost:8000/tmf-api/intentManagement/v5/intent \
  -H "Content-Type: application/json" \
  -d '{
    "@type": "Intent",
    "name": "BBF HSI Service Demo",
    "expression": {
      "@type": "TurtleExpression",
      "iri": "http://broadband-forum.org/Intent#HSIIntent",
      "expressionValue": "@prefix bbf:  <http://broadband-forum.org/Intent#> .\n@prefix rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .\n@prefix icm:  <http://tio.models.tmforum.org/tio/v3.6.0/IntentCommonModel/> .\n@prefix log:  <http://tio.models.tmforum.org/tio/v3.6.0/LogicalOperators/> .\n@prefix quan: <http://tio.models.tmforum.org/tio/v3.6.0/QuantityOntology/> .\n@prefix xsd:  <http://www.w3.org/2001/XMLSchema#> .\n\nbbf:HSICompositeExpectation\n    log:allOf (\n        bbf:DeliveryCheck\n        bbf:UNICheck\n        bbf:PerformanceCheck\n    ) .\n\nbbf:DeliveryCheck  a icm:DeliveryExpectation ;\n    icm:target       bbf:SelectedUNIInterface ;\n    icm:deliveryType bbf:HSIService .\n\nbbf:SelectedUNIInterface\n    rdfs:member bbf:HSIServiceInstance .\n\nbbf:HSIServiceInstance  a bbf:HSIService .\n\nbbf:UNICheck\n    log:allOf (\n        bbf:UNIUpCondition\n        bbf:UNIReadyCondition\n    ) .\n\nbbf:UNIUpCondition\n    log:match ( bbf:SelectedUNIInterface\n                bbf:operationalState\n                bbf:OperationalUp ) .\n\nbbf:UNIReadyCondition\n    log:match ( bbf:SelectedUNIInterface\n                bbf:provisioningState\n                bbf:Ready ) .\n\nbbf:SelectedUNIInterface\n    bbf:operationalState  bbf:OperationalUp ;\n    bbf:provisioningState bbf:Ready .\n\nbbf:PerformanceCheck\n    log:allOf (\n        bbf:DL_Check\n        bbf:UL_Check\n        bbf:Lat_Check\n        bbf:Jit_Check\n        bbf:PL_Check\n    ) .\n\nbbf:DL_Check  quan:atLeast ( bbf:DownstreamBandwidthMetric\n              [ rdf:value \"100\"^^xsd:decimal ] ) .\n\nbbf:UL_Check  quan:atLeast ( bbf:UpstreamBandwidthMetric\n              [ rdf:value \"20\"^^xsd:decimal ] ) .\n\nbbf:Lat_Check  quan:smaller ( bbf:LatencyMetric\n               [ rdf:value \"25\"^^xsd:decimal ] ) .\n\nbbf:Jit_Check  quan:smaller ( bbf:JitterMetric\n               [ rdf:value \"3\"^^xsd:decimal ] ) .\n\nbbf:PL_Check  quan:smaller ( bbf:PacketLossMetric\n              [ rdf:value \"0.1\"^^xsd:decimal ] ) .\n"
    }
  }')

echo "$HSI_INTENT" | python3 -m json.tool
INTENT_ID=$(echo "$HSI_INTENT" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")
echo "Intent ID: $INTENT_ID"
```

The API returns `201 Created`. The evaluator fires in the background immediately.

---

## Step 2 — Activate the intent

Evaluation only runs for `ACTIVE` intents (and `ACKNOWLEDGED` ProbeIntents).

```bash
curl -s -X PATCH \
  "http://localhost:8000/tmf-api/intentManagement/v5/intent/$INTENT_ID" \
  -H "Content-Type: application/json" \
  -d '{"lifecycleStatus": "ACTIVE"}' | python3 -m json.tool
```

Expected: `"lifecycleStatus": "ACTIVE"`. The activation triggers another evaluation cycle.

---

## Step 3 — Check the initial report (Degraded — no observations)

The performance conditions reference metric URIs with no observation records yet.

```bash
sleep 1

curl -s "http://localhost:8000/tmf-api/intentManagement/v5/intent/$INTENT_ID/intentReport" \
  | python3 -m json.tool
```

> Reports are returned **newest first**. The first item is always the latest result.

Expected `intentHandlingState`: **`Degraded`**

```json
[
  {
    "intentHandlingState": "Degraded",
    "intentHandlingReason": "No observation for metric ...",
    ...
  }
]
```

---

## Step 4 — Submit observations (all conditions satisfied)

### Metric URIs

| Metric | URI |
|---|---|
| Downstream bandwidth | `http://broadband-forum.org/Intent#DownstreamBandwidthMetric` |
| Upstream bandwidth | `http://broadband-forum.org/Intent#UpstreamBandwidthMetric` |
| Latency | `http://broadband-forum.org/Intent#LatencyMetric` |
| Jitter | `http://broadband-forum.org/Intent#JitterMetric` |
| Packet loss | `http://broadband-forum.org/Intent#PacketLossMetric` |

> All steps must run in the **same shell session** so that `$INTENT_ID` is in scope.
> To resume in a new terminal: `INTENT_ID="<id from step 1>"`

```bash
BASE="http://localhost:8000/tmf-api/intentManagement/v5/intent/$INTENT_ID/observation"
BBF="http://broadband-forum.org/Intent#"

# Downstream bandwidth: 150 Mbps  (threshold ≥ 100)
curl -s -X POST "$BASE" -H "Content-Type: application/json" \
  -d "{\"metricUri\": \"${BBF}DownstreamBandwidthMetric\", \"value\": 150.0}"

# Upstream bandwidth: 30 Mbps  (threshold ≥ 20)
curl -s -X POST "$BASE" -H "Content-Type: application/json" \
  -d "{\"metricUri\": \"${BBF}UpstreamBandwidthMetric\", \"value\": 30.0}"

# Latency: 8 ms  (threshold < 25)
curl -s -X POST "$BASE" -H "Content-Type: application/json" \
  -d "{\"metricUri\": \"${BBF}LatencyMetric\", \"value\": 8.0}"

# Jitter: 1.2 ms  (threshold < 3)
curl -s -X POST "$BASE" -H "Content-Type: application/json" \
  -d "{\"metricUri\": \"${BBF}JitterMetric\", \"value\": 1.2}"

# Packet loss: 0.02 %  (threshold < 0.1)
curl -s -X POST "$BASE" -H "Content-Type: application/json" \
  -d "{\"metricUri\": \"${BBF}PacketLossMetric\", \"value\": 0.02}"
```

Each POST schedules a background re-evaluation.

---

## Step 5 — Verify Fulfilled state

```bash
sleep 2

curl -s "http://localhost:8000/tmf-api/intentManagement/v5/intent/$INTENT_ID/intentReport" \
  | python3 -m json.tool
```

Expected:

```json
[
  {
    "intentHandlingState": "Fulfilled",
    "intentHandlingReason": null,
    ...
  }
]
```

The `handlerState` named graph in TDB2 now holds per-condition RDF facts:

```
<intent> imo:intentHandlingState imo:Fulfilled .
<condition/0> imo:conditionPassed "true"^^xsd:boolean ;
              imo:observedValue   "150" ;
              imo:boundValue      "100" .
```

---

## Step 6 — Violate one condition (latency spike)

```bash
# Latency: 40 ms  (threshold < 25 — VIOLATED)
curl -s -X POST "$BASE" -H "Content-Type: application/json" \
  -d "{\"metricUri\": \"${BBF}LatencyMetric\", \"value\": 40.0}"

sleep 1

curl -s "http://localhost:8000/tmf-api/intentManagement/v5/intent/$INTENT_ID/intentReport" \
  | python3 -m json.tool
```

Expected: `"intentHandlingState": "Degraded"`. Only the failing condition appears
in `intentHandlingReason`.

---

## Step 7 — Self-heal

```bash
# Latency: 12 ms  (back within threshold)
curl -s -X POST "$BASE" -H "Content-Type: application/json" \
  -d "{\"metricUri\": \"${BBF}LatencyMetric\", \"value\": 12.0}"

sleep 1

curl -s "http://localhost:8000/tmf-api/intentManagement/v5/intent/$INTENT_ID/intentReport" \
  | python3 -c "import sys,json; r=json.load(sys.stdin); print(r[0]['intentHandlingState'])"
```

Expected: **`Fulfilled`** — the system self-heals as soon as a within-threshold
observation arrives.

---

## Step 8 — Force re-evaluation via PATCH

```bash
curl -s -X PATCH \
  "http://localhost:8000/tmf-api/intentManagement/v5/intent/$INTENT_ID" \
  -H "Content-Type: application/json" \
  -d '{"description": "force re-evaluation"}'
```

Every PATCH schedules an evaluation cycle using the current observation graph.

---

## Step 9 — Flow 1: ProbeIntent (capability probe)

A ProbeIntent auto-transitions after evaluation:
- `Fulfilled` → `ACTIVE`
- `Degraded` → `TERMINATED`

### 9a — Probe that passes

```bash
PROBE_PASS=$(curl -s -X POST http://localhost:8000/tmf-api/intentManagement/v5/intent \
  -H "Content-Type: application/json" \
  -d "{
    \"@type\": \"ProbeIntent\",
    \"name\": \"HSI Capability Probe — pass\",
    \"expression\": {
      \"@type\": \"TurtleExpression\",
      \"expressionValue\": \"@prefix bbf: <http://broadband-forum.org/Intent#> .\\n@prefix log: <http://tio.models.tmforum.org/tio/v3.6.0/LogicalOperators/> .\\n\\nbbf:Check log:match ( bbf:UNI bbf:operationalState bbf:Up ) .\\nbbf:UNI bbf:operationalState bbf:Up .\\n\"
    }
  }")

PROBE_ID=$(echo "$PROBE_PASS" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")

sleep 1
curl -s "http://localhost:8000/tmf-api/intentManagement/v5/intent/$PROBE_ID" \
  | python3 -c "import sys,json; print('Status:', json.load(sys.stdin)['lifecycleStatus'])"
```

Expected: **`ACTIVE`**

### 9b — Probe that fails

```bash
PROBE_FAIL=$(curl -s -X POST http://localhost:8000/tmf-api/intentManagement/v5/intent \
  -H "Content-Type: application/json" \
  -d "{
    \"@type\": \"ProbeIntent\",
    \"name\": \"HSI Capability Probe — fail\",
    \"expression\": {
      \"@type\": \"TurtleExpression\",
      \"expressionValue\": \"@prefix bbf: <http://broadband-forum.org/Intent#> .\\n@prefix log: <http://tio.models.tmforum.org/tio/v3.6.0/LogicalOperators/> .\\n\\nbbf:Check log:match ( bbf:UNI bbf:operationalState bbf:Shutdown ) .\\n\"
    }
  }")

PROBE_FAIL_ID=$(echo "$PROBE_FAIL" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")

sleep 1
curl -s "http://localhost:8000/tmf-api/intentManagement/v5/intent/$PROBE_FAIL_ID" \
  | python3 -c "import sys,json; print('Status:', json.load(sys.stdin)['lifecycleStatus'])"
```

Expected: **`TERMINATED`**

---

## Step 10 — Flow 3: Best/Propose

When an ACKNOWLEDGED or ACTIVE intent evaluates as Degraded, the handler:
1. Substitutes the observed value as the new bound for each failed condition
2. PATCHes the intent's `expressionValue` with the updated Turtle
3. Fires `IntentAttributeValueChangeEvent`

The intent stays ACKNOWLEDGED — the owner must explicitly PATCH to ACTIVE to accept.

### 10a — Create intent with tight bounds

```bash
STRICT=$(curl -s -X POST http://localhost:8000/tmf-api/intentManagement/v5/intent \
  -H "Content-Type: application/json" \
  -d '{
    "@type": "Intent",
    "name": "Flow3 Demo — tight bounds",
    "expression": {
      "@type": "TurtleExpression",
      "expressionValue": "@prefix bbf: <http://broadband-forum.org/Intent#> .\n@prefix quan: <http://tio.models.tmforum.org/tio/v3.6.0/QuantityOntology/> .\n@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .\n\nbbf:DL_Check  quan:atLeast ( bbf:DownstreamBandwidthMetric\n              [ rdf:value \"500\"^^xsd:decimal ] ) .\n"
    }
  }')

STRICT_ID=$(echo "$STRICT" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")
echo "Strict intent: $STRICT_ID"
```

### 10b — Activate and submit an observation

```bash
curl -s -X PATCH \
  "http://localhost:8000/tmf-api/intentManagement/v5/intent/$STRICT_ID" \
  -H "Content-Type: application/json" \
  -d '{"lifecycleStatus": "ACTIVE"}' > /dev/null

# System can only deliver 150 Mbps — below the 500 Mbps bound
curl -s -X POST \
  "http://localhost:8000/tmf-api/intentManagement/v5/intent/$STRICT_ID/observation" \
  -H "Content-Type: application/json" \
  -d "{\"metricUri\": \"http://broadband-forum.org/Intent#DownstreamBandwidthMetric\", \"value\": 150.0}"

sleep 2
```

### 10c — Inspect the proposal

```bash
curl -s "http://localhost:8000/tmf-api/intentManagement/v5/intent/$STRICT_ID" \
  | python3 -c "
import sys, json
d = json.load(sys.stdin)
expr = d.get('expression', {}).get('expressionValue', '')
print('lifecycleStatus:', d.get('lifecycleStatus'))
print()
print('expressionValue (bound should now be 150, not 500):')
print(expr[:400])
"
```

The bound `500` is replaced by the observed value `150`.

### 10d — Accept the proposal

```bash
curl -s -X PATCH \
  "http://localhost:8000/tmf-api/intentManagement/v5/intent/$STRICT_ID" \
  -H "Content-Type: application/json" \
  -d '{"lifecycleStatus": "ACTIVE"}' \
  | python3 -c "import sys,json; print('Status:', json.load(sys.stdin)['lifecycleStatus'])"
```

Expected: **`ACTIVE`** — the next evaluation cycle with the updated bound will produce `Fulfilled`.

---

## Condition reference

| Condition | Type | Threshold | Pass example | Fail example |
|---|---|---|---|---|
| Downstream BW | `quan:atLeast` | ≥ 100 Mbps | 150 | 80 |
| Upstream BW | `quan:atLeast` | ≥ 20 Mbps | 30 | 15 |
| Latency | `quan:smaller` | < 25 ms | 8 | 40 |
| Jitter | `quan:smaller` | < 3 ms | 1.2 | 5 |
| Packet loss | `quan:smaller` | < 0.1 % | 0.02 | 0.2 |
| UNI operational | `log:match` | operationalState = OperationalUp | asserted inline | absent |
| UNI ready | `log:match` | provisioningState = Ready | asserted inline | absent |
| Service delivery | `icm:DeliveryExpectation` | target has HSIService member | `rdfs:member` inline | absent |

---

## Code locations (Java)

| Class | What it does |
|---|---|
| `TurtleEvaluator.evaluate()` | Parses Turtle, runs 8-pass pipeline, walks TIO tree |
| `TurtleEvaluator.resolveMetricRefs()` | Injects latest `met:Observation` by `obtainedAt` |
| `TurtleEvaluator.evalDeliveryExpectation()` | Checks `icm:target` container membership |
| `TurtleEvaluator.evalMatch()` | Checks a `(s, p, o)` triple exists |
| `TurtleEvaluator.evalTwoArg()` | Evaluates `atLeast`, `smaller`, etc. |
| `HandlerStateWriter.writeHandlerState()` | Serialises per-condition results to TDB2 |
| `ObservationStore.writeObservation()` | Appends `met:Observation` to observations graph |
| `EvaluationDispatcher.runEvaluation()` | Orchestrates evaluate → write state → create report → notify |
| `EvaluationDispatcher.tryProbeTransition()` | Flow 1: auto-transitions ProbeIntent |
| `EvaluationDispatcher.tryBestPropose()` | Flow 3: substitutes best-effort bounds |
| `BestEffortLimits.apply()` | Finds failed quantity nodes, updates bound `rdf:value` |
| `ObservationController` | `POST /intent/{id}/observation` endpoint |

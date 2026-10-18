# Access Domain HSI Demo — End-to-End Walkthrough (Java)

This guide exercises the BBF access-domain resource allocation flow using the
TMF921 API under the `access` Spring profile. It verifies that:

1. PON resource inventory loads at startup
2. Set-constructor conditions (`set:resourcesOfType`, `set:resourcesWithPropertyObject`) resolve correctly
3. A Fulfilled evaluation triggers Flow 4 resource write-back (`pon:inUse true`, `pon:assignedToService`)
4. Idempotent re-evaluation does not double-allocate

> For the simpler metric-only HSI demo (no resource inventory), see [`HSI_DEMO.md`](HSI_DEMO.md).

---

## Architecture

```
POST /intent          →  TDB2 intent named graph
POST /observation     →  TDB2 observations graph  (per intent)
                                 ↓ merged at evaluation time
         TurtleEvaluator.evaluate()   ← Java, embedded Jena, access profile
                                 ↓
         Set constructors resolve against resource graph (TDB2)
                                 ↓
         Fulfilled → Flow 4: HandlerStateWriter.writeResourceAllocation()
                           ← pon:inUse true  +  pon:assignedToService <intent>
```

The `access` profile loads `BBF_access/pon_resource_data.ttl` and `BBF_access/pon_resource_onto.ttl`
into TDB2 at startup via `SchemaInit.loadResourceInventory()`. The evaluator reads
this graph at evaluation time when resolving `set:resourcesOfType` and
`set:resourcesWithPropertyObject` set constructors.

---

## Prerequisites

### Start the access-profile server

**Docker (recommended):**

```bash
docker compose down -v                            # clear any previous state
docker compose --profile access up --build
```

**Dev server:**

```bash
make clean         # removes tdb2-data/, tdb2-access/, tdb2-aggregation/
make run-access    # or: mvn spring-boot:run -Dspring-boot.run.profiles=access
```

Wait for:
```
[SchemaInit] Loaded PON resource inventory: 42 triples
Started Application in N.NNN seconds
```

### Optional: enable Fuseki (SPARQL + web UI on port 3030)

Add the `fuseki` Spring profile to browse the live TDB2 graphs, including the PON resource graph, in a browser:

```bash
# Docker
APP_PROFILES=access,fuseki docker compose --profile access up --build

# Dev server
mvn spring-boot:run -Dspring-boot.run.profiles=access,fuseki
```

Wait for `Start Fuseki (http=3030)`, then open <http://localhost:3030> (dataset `/intents`, read-only).
Query endpoint: `http://localhost:3030/intents/sparql`.

### Verify (port 8001 for access profile)

```bash
curl http://localhost:8001/health
```

Expected:
```json
{"status": "UP", "graph": "UP"}
```

> All steps below use port **8001**. Set once: `BASE=http://localhost:8001/tmf-api/intentManagement/v5`

---

## The intent expression

The HSI expression (`seed_data/hsionlyintent_v0.5.ttl`) uses TIO set constructors:

```
┌─ log:allOf ─────────────────────────────────────────────────────────┐
│                                                                      │
│  icm:DeliveryExpectation                                             │
│    icm:target  set:SelectedUNIs                                      │
│    icm:deliveryType  bbf:HSIService                                  │
│                                                                      │
│  set:SelectedUNIs                                                    │
│    set:resourcesOfType pon:UNIPort                                   │
│    set:resourcesWithPropertyObject (pon:inUse false)                 │
│    set:resourcesWithPropertyObject (pon:operationalState pon:Up)     │
│                                                                      │
│  log:allOf  (5× metric conditions)                                   │
│    quan:atLeast DownstreamBandwidthMetric 100                        │
│    quan:atLeast UpstreamBandwidthMetric 20                           │
│    quan:smaller LatencyMetric 25                                     │
│    quan:smaller JitterMetric 3                                       │
│    quan:smaller PacketLossMetric 0.1                                 │
└──────────────────────────────────────────────────────────────────────┘
```

`set:SelectedUNIs` resolves to all UNI ports that are:
- typed `pon:UNIPort`
- have `pon:inUse false`
- have `pon:operationalState pon:Up`

On Fulfilled evaluation, Flow 4 writes `pon:inUse true` and
`pon:assignedToService <intent-uri>` to the matched ports.

---

## Step 1 — Create the HSI intent

Set `BASE` and create the intent from the seeded expression file:

```bash
BASE=http://localhost:8001/tmf-api/intentManagement/v5

EXPR=$(cat seed_data/hsionlyintent_v0.5.ttl)

HSI_INTENT=$(curl -s -X POST "$BASE/intent" \
  -H "Content-Type: application/json" \
  -d "{
    \"@type\": \"Intent\",
    \"name\": \"BBF Access HSI Demo\",
    \"expression\": {
      \"@type\": \"TurtleExpression\",
      \"iri\": \"http://broadband-forum.org/Intent#HSIOnlyIntent\",
      \"expressionValue\": $(python3 -c "import sys, json; print(json.dumps(open('seed_data/hsionlyintent_v0.5.ttl').read()))")
    }
  }")

echo "$HSI_INTENT" | python3 -m json.tool
INTENT_ID=$(echo "$HSI_INTENT" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")
echo "Intent ID: $INTENT_ID"
```

---

## Step 2 — Activate

```bash
curl -s -X PATCH "$BASE/intent/$INTENT_ID" \
  -H "Content-Type: application/json" \
  -d '{"lifecycleStatus": "ACTIVE"}' | python3 -m json.tool
```

---

## Step 3 — Verify free UNIs exist in the inventory

Before posting observations, confirm the resource graph has available UNI ports.
The evaluator will resolve `set:SelectedUNIs` against these.

```bash
# List all intent reports to see initial state
sleep 1
curl -s "$BASE/intent/$INTENT_ID/intentReport" | python3 -m json.tool
```

The initial report shows **`Degraded`** because no metric observations exist yet.

---

## Step 4 — Submit observations (all metrics pass)

```bash
OBS="$BASE/intent/$INTENT_ID/observation"
BBF="http://broadband-forum.org/Intent#"

curl -s -X POST "$OBS" -H "Content-Type: application/json" \
  -d "{\"metricUri\": \"${BBF}DownstreamBandwidthMetric\", \"value\": 150.0}"

curl -s -X POST "$OBS" -H "Content-Type: application/json" \
  -d "{\"metricUri\": \"${BBF}UpstreamBandwidthMetric\", \"value\": 30.0}"

curl -s -X POST "$OBS" -H "Content-Type: application/json" \
  -d "{\"metricUri\": \"${BBF}LatencyMetric\", \"value\": 8.0}"

curl -s -X POST "$OBS" -H "Content-Type: application/json" \
  -d "{\"metricUri\": \"${BBF}JitterMetric\", \"value\": 1.2}"

curl -s -X POST "$OBS" -H "Content-Type: application/json" \
  -d "{\"metricUri\": \"${BBF}PacketLossMetric\", \"value\": 0.02}"

echo "Waiting for evaluation..."
sleep 2
```

---

## Step 5 — Verify Fulfilled + resource allocation

```bash
# Check latest report
curl -s "$BASE/intent/$INTENT_ID/intentReport" \
  | python3 -c "
import sys, json
reports = json.load(sys.stdin)
r = reports[0]
print('intentHandlingState:', r.get('intentHandlingState'))
print('reason:             ', r.get('intentHandlingReason'))
"
```

Expected: `intentHandlingState: Fulfilled`

### Verify resource write-back

When the intent becomes `Fulfilled`, the handler picks one free UNI port and one free C-TAG from the
PON inventory and records the allocation on them in the resource graph:
`pon:inUse true` and `pon:assignedToService "<intent-id>"`.

**1. Confirm the intent itself is still active.** This only reads the intent resource (its type and
`lifecycleStatus`); it does *not* show the allocation:

```bash
curl -s "$BASE/intent/$INTENT_ID" \
  | python3 -c "
import sys, json
d = json.load(sys.stdin)
print('@type:           ', d.get('@type'))
print('lifecycleStatus: ', d.get('lifecycleStatus'))
"
```

Expected: `@type: Intent`, `lifecycleStatus: ACTIVE`.

**2. Show what this intent allocated.** The allocation lives only in the TDB2 resource graph, so it
cannot be read through the TMF921 API. With Fuseki enabled (`access,fuseki` profiles, see above) query
it by intent ID, either in the Fuseki UI query tab at <http://localhost:3030> or with curl:

```bash
curl -s -G http://localhost:3030/intents/sparql \
  -H 'Accept: text/csv' \
  --data-urlencode "query=PREFIX pon: <http://broadband-forum.org/ont/pon-resource#>
SELECT ?resource ?type ?cTag ?sTag WHERE {
  GRAPH <http://tmforum.org/api/v5/resources> {
    ?resource a ?type ;
              pon:inUse true ;
              pon:assignedToService \"$INTENT_ID\" .
    OPTIONAL { ?resource pon:cTag ?cTag ;
                         pon:fromSVLAN ?svlan .
               ?svlan pon:sTag ?sTag }
  }
}"
```

Expected: two rows for your intent ID:

| Row | `type` | `cTag` / `sTag` | Meaning |
|---|---|---|---|
| 1 | `pon:UNIPort` | empty | The subscriber-facing port allocated to the service (e.g. `UNI-003-1`) |
| 2 | `pon:CTAGAllocation` | e.g. `201` / `100` | The inner VLAN (C-tag) taken from the S-VLAN pool (S-tag 100); the `N`/`S` in `CTAG-N-002` / `CTAG-S-002` is the North/South pool, not "S-tag" |

Both rows must carry your intent ID. Resources that belonged to the initial inventory (`UNI-002-1`,
`CTAG-N-002`) show `existing-service-placeholder` instead; those were already in use before the demo.

To list every allocation in the graph, whichever service owns it, drop the intent filter:

```sparql
PREFIX pon: <http://broadband-forum.org/ont/pon-resource#>
SELECT ?resource ?svc WHERE {
  GRAPH <http://tmforum.org/api/v5/resources> {
    ?resource pon:inUse true ; pon:assignedToService ?svc .
  }
}
```

Each resource should appear **once**. If the same port or C-tag appears with two different service IDs,
two intents were allocated the same resource. The write-back only claims a resource that is still
`pon:inUse false`, so this should not happen; start from a clean store (`docker compose down -v`) and
repeat to confirm.

Without Fuseki, for direct TDB2 verification, run the AccessDemoIT integration test which performs
a SPARQL ASK query against the embedded dataset:

```bash
mvn test -Dtest=AccessDemoIT -pl . 2>&1 | grep -E "(PASSED|FAILED|Tests run)"
```

Expected:
```
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
```

The test class `AccessDemoIT` uses `@Autowired Dataset` to execute the query below. It also works as-is in the
Fuseki UI (`access,fuseki` profiles): replace `INTENT_ID` with your intent's UUID, and keep the `PREFIX` line,
without it Fuseki rejects the query with "Unresolved prefixed name: pon:UNIPort". It returns `true` if the intent
has been allocated a UNI port.

```sparql
PREFIX pon: <http://broadband-forum.org/ont/pon-resource#>
ASK {
  GRAPH <http://tmforum.org/api/v5/resources> {
    ?port a pon:UNIPort ;
          pon:inUse true ;
          pon:assignedToService ?svc .
    FILTER(CONTAINS(STR(?svc), "INTENT_ID"))
  }
}
```

---

## Step 6 — Verify idempotency

Post the same observations a second time:

```bash
curl -s -X POST "$OBS" -H "Content-Type: application/json" \
  -d "{\"metricUri\": \"${BBF}DownstreamBandwidthMetric\", \"value\": 150.0}"

sleep 1

curl -s "$BASE/intent/$INTENT_ID/intentReport" \
  | python3 -c "import sys,json; print('Total reports:', len(json.load(sys.stdin)))"
```

Each observation triggers an evaluation cycle and creates a new IntentReport.
Resource allocation does NOT double-write — the idempotency guard in
`HandlerStateWriter.resourcesAlreadyAllocated()` prevents repeated `pon:inUse` updates.

---

## Step 7 — Exhaust the inventory

To trigger `Degraded` via exhausted resources, patch a test expression that requires
a type with no free instances. (The PON inventory in `pon_resource_data.ttl` may be
fully allocated after Step 5.)

### 7a — Create a competing intent

```bash
COMP=$(curl -s -X POST "$BASE/intent" \
  -H "Content-Type: application/json" \
  -d "{
    \"@type\": \"Intent\",
    \"name\": \"Competing HSI intent\",
    \"expression\": {
      \"@type\": \"TurtleExpression\",
      \"expressionValue\": $(python3 -c "import sys, json; print(json.dumps(open('seed_data/hsionlyintent_v0.5.ttl').read()))")
    }
  }")

COMP_ID=$(echo "$COMP" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")

curl -s -X PATCH "$BASE/intent/$COMP_ID" \
  -H "Content-Type: application/json" \
  -d '{"lifecycleStatus": "ACTIVE"}' > /dev/null
```

### 7b — Post the same metric observations

```bash
COMP_OBS="$BASE/intent/$COMP_ID/observation"

curl -s -X POST "$COMP_OBS" -H "Content-Type: application/json" \
  -d "{\"metricUri\": \"${BBF}DownstreamBandwidthMetric\", \"value\": 150.0}"
# ... (repeat for all 5 metrics)

sleep 2

curl -s "$BASE/intent/$COMP_ID/intentReport" \
  | python3 -c "import sys,json; r=json.load(sys.stdin); print('State:', r[0]['intentHandlingState'])"
```

If no free `pon:inUse false` UNI ports remain, the set constructor resolves to an
empty set → the `DeliveryExpectation` fails → `Degraded`.

---

## Step 8 — Multiple concurrent intents (allOf composition)

You can compose a multi-condition `log:allOf` expression spanning both structural
and metric conditions. The evaluator handles all condition types in a single pass.

```bash
MULTI_INTENT=$(curl -s -X POST "$BASE/intent" \
  -H "Content-Type: application/json" \
  -d '{
    "@type": "Intent",
    "name": "Multi-condition test",
    "expression": {
      "@type": "TurtleExpression",
      "expressionValue": "@prefix bbf: <http://broadband-forum.org/Intent#> .\n@prefix log: <http://tio.models.tmforum.org/tio/v3.6.0/LogicalOperators/> .\n@prefix quan: <http://tio.models.tmforum.org/tio/v3.6.0/QuantityOntology/> .\n@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .\n\nbbf:Top log:allOf (\n  bbf:DL_Check\n  bbf:UL_Check\n) .\nbbf:DL_Check quan:atLeast ( <http://broadband-forum.org/Intent#DownstreamBandwidthMetric> [ rdf:value \"100\"^^xsd:decimal ] ) .\nbbf:UL_Check quan:atLeast ( <http://broadband-forum.org/Intent#UpstreamBandwidthMetric> [ rdf:value \"20\"^^xsd:decimal ] ) .\n"
    }
  }')

MULTI_ID=$(echo "$MULTI_INTENT" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")

curl -s -X PATCH "$BASE/intent/$MULTI_ID" \
  -H "Content-Type: application/json" \
  -d '{"lifecycleStatus": "ACTIVE"}' > /dev/null

# Post only DL — allOf should remain Degraded (UL missing)
curl -s -X POST "$BASE/intent/$MULTI_ID/observation" \
  -H "Content-Type: application/json" \
  -d "{\"metricUri\": \"${BBF}DownstreamBandwidthMetric\", \"value\": 150.0}"

sleep 1

curl -s "$BASE/intent/$MULTI_ID/intentReport" \
  | python3 -c "import sys,json; r=json.load(sys.stdin); print('Partial state:', r[0]['intentHandlingState'])"
# Expected: Degraded

# Now post UL — allOf should become Fulfilled
curl -s -X POST "$BASE/intent/$MULTI_ID/observation" \
  -H "Content-Type: application/json" \
  -d "{\"metricUri\": \"${BBF}UpstreamBandwidthMetric\", \"value\": 25.0}"

sleep 1

curl -s "$BASE/intent/$MULTI_ID/intentReport" \
  | python3 -c "import sys,json; r=json.load(sys.stdin); print('Full state:', r[0]['intentHandlingState'])"
# Expected: Fulfilled
```

---

## PON resource schema

`BBF_access/pon_resource_onto.ttl` defines:

| Class | Description |
|---|---|
| `pon:OLT` | Optical Line Terminal |
| `pon:PONPort` | PON port on an OLT |
| `pon:ONT` | Optical Network Terminal |
| `pon:UNIPort` | User Network Interface port |

Key predicates used by set constructors:

| Predicate | Type | Values |
|---|---|---|
| `pon:inUse` | xsd:boolean | `true` (allocated) / `false` (free) |
| `pon:operationalState` | URI | `pon:Up`, `pon:Down` |
| `pon:assignedToService` | URI | intent named graph URI |
| `pon:connectedTo` | URI | parent device |

---

## Set constructor reference

| Constructor | What it selects |
|---|---|
| `set:resourcesOfType T` | All resources in resource graph typed `T` |
| `set:resourcesWithPropertyObject (P V)` | All resources with predicate P and object V |
| `set:union (A B)` | Union of sets A and B |
| `set:intersection (A B)` | Intersection of sets A and B |
| `set:difference (A B)` | A minus B |

Results are materialised as `rdfs:member` triples on the constructor node, then
referenced by `icm:DeliveryExpectation` and `icm:target`.

---

## Code locations (Java — access-domain path)

| Class | What it does |
|---|---|
| `SchemaInit.loadResourceInventory()` | Loads `BBF_access/*.ttl` into TDB2 resource graph |
| `TurtleEvaluator.computeSetConstructors()` | Resolves `set:resourcesOfType`, `set:resourcesWithPropertyObject` |
| `TurtleEvaluator.evalDeliveryExpectation()` | Checks `icm:target` container has members of `icm:deliveryType` |
| `HandlerStateWriter.writeResourceAllocation()` | Writes `pon:inUse true` + `pon:assignedToService` |
| `HandlerStateWriter.resourcesAlreadyAllocated()` | ASK-query idempotency guard |
| `EvaluationDispatcher.tryResourceAllocation()` | Flow 4: calls writeResourceAllocation on Fulfilled |
| `AccessDemoIT` | Integration test: verifies resource graph via `@Autowired Dataset` |

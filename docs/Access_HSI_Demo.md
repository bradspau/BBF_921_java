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

### Start the access-profile server, with Fuseki

Steps 1b, 4 and 5 query the graph store through Fuseki (SPARQL + web UI on port 3030), so start the server with
both the `access` and `fuseki` profiles.

**Docker (recommended):**

```bash
docker compose --profile access down -v             # clear any previous state (--profile is required)
APP_PROFILES=access,fuseki docker compose --profile access up --build
```

**Dev server:**

```bash
make clean         # removes tdb2-data/, tdb2-access/, tdb2-aggregation/
mvn spring-boot:run -Dspring-boot.run.profiles=access,fuseki
```

Wait for:
```
Resource inventory loaded
Start Fuseki (http=3030)
Started Application in N.NNN seconds
```

Fuseki is read-only; the dataset is `/intents`, the query endpoint is `http://localhost:3030/intents/sparql`, and
the web UI is <http://localhost:3030>.

> **A Fuseki `curl` prints nothing at all?** A working query always prints at least a header row (for example
> `uni,ont`), even with no matches. No output means Fuseki is not running: the server was started without the
> `fuseki` profile (plain `docker compose --profile access up` or `make run-access`). Port 3030 is still
> published by Docker, so `curl -s` just returns silently. Check with
> `docker inspect bbf_921_java-app-access-1 --format '{{range .Config.Env}}{{println .}}{{end}}' | grep SPRING_PROFILES`
> (it should list `fuseki`), and restart with the `APP_PROFILES=access,fuseki` command above.

> **Restarts keep allocations.** The PON inventory is loaded only into an empty store, so allocations
> written back by the handler survive an application restart. If you edit `BBF_access/pon_resource_data.ttl`
> and want it re-read, start from a clean store (`docker compose --profile access down -v`, or `make clean`).

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

## Step 1b — Verify free UNIs exist in the inventory

Before activating the intent, confirm the resource graph contains at least one UNI port the evaluator
can select; the intent can only reach `Fulfilled`, and allocate resources, if it does.
`set:SelectedUNIs` in the intent expression picks UNI ports that are not in use (`pon:inUse false`),
operationally `Up` and provisioning `Ready`.

This reads the TDB2 resource graph directly, which is not exposed through the TMF921 API, so it needs
the Fuseki profile (`access,fuseki`, see above). Run it in the Fuseki UI query tab at
<http://localhost:3030> or with curl:

```bash
curl -s -G http://localhost:3030/intents/sparql \
  -H 'Accept: text/csv' \
  --data-urlencode 'query=PREFIX pon: <http://broadband-forum.org/ont/pon-resource#>
SELECT ?uni ?ont WHERE {
  GRAPH <http://tmforum.org/api/v5/resources> {
    ?uni a pon:UNIPort ;
         pon:inUse false ;
         pon:operationalState pon:Up ;
         pon:provisioningState pon:Ready ;
         pon:belongsToONT ?ont .
  }
} ORDER BY ?uni'
```

Expected on a clean store: **8 rows** (`UNI-001-1`, `UNI-001-2`, `UNI-002-2`, `UNI-003-1`, `UNI-003-2`,
`UNI-004-1`, `UNI-004-2`, `UNI-005-2`). The inventory has 10 UNI ports; the other two are deliberately
excluded by the seed data in `BBF_access/pon_resource_data.ttl`, so you can see the filter working:

| UNI | Why it is not listed |
|---|---|
| `UNI-002-1` | Defined as **in use** (`pon:inUse true`, assigned to `existing-service-placeholder`), so it fails the `pon:inUse false` condition. It represents a port already serving another subscriber. |
| `UNI-005-1` | Defined as **operationally down** (`pon:operationalState pon:Down`), so it fails the `pon:operationalState pon:Up` condition. It is not in use, but a faulty port must not be allocated. |

If the query returns no rows, every UNI is allocated or unavailable and the intent cannot reach
`Fulfilled`; restart from a clean store (`docker compose --profile access down -v`).

The intent also selects an S-tag and a C-tag. The HSI service is carried on an 802.1ad dual-tagged VLAN: the
S-tag identifies the S-VLAN and the C-tag is an inner tag that is unique only *within* that S-VLAN. So the S-tag
is chosen first, from S-VLANs whose `pon:serviceType` is `pon:HSIService`, and the C-tag must then come from the
S-VLAN that was chosen, never from another one. A clean store has three S-VLANs: `SVLAN-N-HSI` and
`SVLAN-S-HSI` (both HSI, S-tag 100) and `SVLAN-N-VoIP` (VoIP, so never used for HSI). There are 6 free C-tags,
all on the two HSI S-VLANs. Each
delivery expectation in the intent names the class of resource it must deliver (`icm:deliveryType`):
`pon:UNIPort`, `pon:SVLANAllocation` and `pon:CTAGAllocation`. `pon:HSIService` is the delivery description.

---

## Step 2 — Activate

```bash
curl -s -X PATCH "$BASE/intent/$INTENT_ID" \
  -H "Content-Type: application/json" \
  -d '{"lifecycleStatus": "ACTIVE"}' | python3 -m json.tool
```

---

## Step 3 — Check the initial intent report

Before posting observations, read the intent's report to see its starting state:

```bash
# List all intent reports to see initial state
sleep 1
curl -s "$BASE/intent/$INTENT_ID/intentReport" | python3 -m json.tool
```

The initial report shows **`Degraded`** because no metric observations exist yet.

---

## Step 4 — Show the intent is not Fulfilled until every condition is met

The intent has **eight** conditions, all of which must pass:

| # | Condition | Type | Passes when |
|---|---|---|---|
| 1 | UNI selection | `DeliveryExpectation` | a free, `Up`, `Ready` `pon:UNIPort` can be chosen |
| 2 | S-tag selection | `DeliveryExpectation` | an HSI `pon:SVLANAllocation` can be chosen |
| 3 | C-tag selection | `DeliveryExpectation` | a free `pon:CTAGAllocation` from the S-VLAN chosen in condition 2 can be chosen |
| 4 | Downstream bandwidth | `atLeast` | observed ≥ 100 Mbps |
| 5 | Upstream bandwidth | `atLeast` | observed ≥ 20 Mbps |
| 6 | Latency | `smaller` | observed < 25 ms |
| 7 | Jitter | `smaller` | observed < 3 ms |
| 8 | Packet loss | `smaller` | observed < 0.1 % |

Conditions 1–3 depend only on the inventory, so they pass as soon as the intent is active. Conditions 4–8 each
need a metric observation. You will post the metrics in two halves and check the intent report and the
per-condition results after each.

Set up the variables and helpers once. `results` reads the TR290 intent report the handler stores with every
IntentReport: an `icm:result` for the intent, each expectation and each condition. It queries Fuseki, so it needs
the Fuseki profile (`access,fuseki`):

```bash
OBS="$BASE/intent/$INTENT_ID/observation"
BBF="http://broadband-forum.org/Intent#"

post_metric() {   # usage: post_metric DownstreamBandwidthMetric 150.0
  curl -s -o /dev/null -X POST "$OBS" -H "Content-Type: application/json" \
    -d "{\"metricUri\": \"${BBF}$1\", \"value\": $2}"
}

report() {        # latest intent report: state and why
  curl -s "$BASE/intent/$INTENT_ID/intentReport" | python3 -c "
import sys, json
r = json.load(sys.stdin)
print('reports so far:     ', len(r))
print('intentHandlingState:', r[0].get('intentHandlingState'))
print('reason:             ', r[0].get('intentHandlingReason'))
"
}

results() {       # icm:result of every element in this intent's latest TR290 report (Fuseki)
  curl -s -G http://localhost:3030/intents/sparql -H 'Accept: text/csv' \
    --data-urlencode "query=PREFIX icm: <http://tio.models.tmforum.org/tio/v3.6.0/IntentCommonModel/>
PREFIX tmf: <http://tmforum.org/api/v5/>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
SELECT ?kind ?element ?result ?target ?reason WHERE {
  ?report tmf:parentIntent <http://tmforum.org/api/v5/intents/$INTENT_ID> ; tmf:icmReport ?ir .
  ?ir icm:reportNumber ?n .
  FILTER NOT EXISTS {
    ?other tmf:parentIntent <http://tmforum.org/api/v5/intents/$INTENT_ID> ; tmf:icmReport ?ir2 .
    ?ir2 icm:reportNumber ?n2 . FILTER (?n2 > ?n)
  }
  ?ir icm:resultFrom* ?r .
  ?r a ?kind ; icm:about ?element ; icm:result ?result .
  OPTIONAL { ?r icm:targetReport/rdfs:member ?target }
  OPTIONAL { ?r icm:reason/rdfs:label ?reason }
} ORDER BY ?kind ?element" \
  | sed 's|http://tio.models.tmforum.org/tio/v3.6.0/IntentCommonModel/||; s|http://broadband-forum.org/Intent#||g; s|http://broadband-forum.org/ont/pon-resource#||g'
}
```

> **How the intent is reported on (TR290 §4).** Each evaluation creates a TR290 report with one report per intent
> element: an `icm:IntentReport`, an expectation report per expectation (`icm:DeliveryExpectationReport`,
> `icm:PropertyExpectationReport`, ...) and an `icm:ConditionReport` per condition. Each says which element it
> covers (`icm:about`), its truth value (`icm:result`; for the intent and expectations `true` means compliant), and
> which reports it was derived from (`icm:resultFrom`), so the intent's result is built up from its conditions.
> A report that is `false` carries an `icm:reason`. A report for an expectation also names its target's resources
> (`icm:targetReport`), which for a delivery expectation are the resources chosen. The reporting expectation in the intent decides which elements are reported (TR290 §4.2).
> Observed values are left out because it is an `icm:ReportingExpectation`; an `icm:ObservationReportingExpectation`
> would add them (`met:observed`).

### 4a — First half: bandwidth metrics only

```bash
post_metric DownstreamBandwidthMetric 150.0    # >= 100: pass
post_metric UpstreamBandwidthMetric   30.0     # >= 20:  pass
sleep 2
report
```

Expected: **`Degraded`**, with a reason that lists only the three conditions still missing:
`smaller: no observation: LatencyMetric; … JitterMetric; … PacketLossMetric`.

```bash
results
```

Expected: 12 rows, one per reported element, built up from the bottom:

| `kind` | `element` | `result` | Notes |
|---|---|---|---|
| `ConditionReport` | `HSI_DownstreamBandwidthCondition`, `HSI_UpstreamBandwidthCondition` | `true` | |
| `ConditionReport` | `HSI_LatencyCondition`, `HSI_JitterCondition`, `HSI_PacketLossCondition` | `false` | `reason` = `no observation: …` |
| `DeliveryExpectationReport` | the UNI, S-tag and C-tag expectations | `true` | `target` = the resource chosen: `UNI-001-1`, `SVLAN-N-HSI`, `CTAG-N-001` (candidates are tried in name order, so the choice is repeatable) |
| `PropertyExpectationReport` | `HSIPerformanceExpectation` | `false` | `target` = `HSIServiceInstance`, the resource its conditions apply to; five conditions, three of them false |
| `ReportingExpectationReport` | `HSIMonitoringExpectation` | `true` | nothing to fail |
| `ExpectationReport` | `HSICompositeExpectation` | `false` | one of its parts is false |
| `IntentReport` | `HSIIntent` | `false` | not compliant |

Order matters in the intent: the S-tag is selected before the C-tag, because the C-tag pool is built from the
chosen S-VLAN.

Nothing is allocated yet. The UNI, S-tag and C-tag are only *selected*; the handler writes the allocation back
when the intent becomes Fulfilled (see Step 5).

### 4b — Second half: latency, jitter and packet loss

```bash
post_metric LatencyMetric      8.0     # < 25:  pass
post_metric JitterMetric       1.2     # < 3:   pass
sleep 2
report
results
```

Expected: still **`Degraded`**. Only `PacketLossMetric` is left in the reason. In `results`, four of the five
`ConditionReport` rows are now `true` (only `HSI_PacketLossCondition` is `false`, with its reason), and the
performance expectation, the composite expectation and the intent are still `false`.

```bash
post_metric PacketLossMetric   0.02    # < 0.1: pass
echo "Waiting for evaluation..."
sleep 2
report
results
```

Expected: **`Fulfilled`** with reason `None`, and all 12 rows `result = true`, up to and including the
`IntentReport`. The intent became compliant only on the last observation, when the final condition passed.

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
| 1 | `pon:UNIPort` | empty | The subscriber-facing port allocated to the service (e.g. `UNI-001-1`) |
| 2 | `pon:CTAGAllocation` | e.g. `200` / `100` | The inner VLAN (C-tag) taken from the S-VLAN chosen for this service (S-tag 100); the `N`/`S` in `CTAG-N-001` / `CTAG-S-001` is the North/South S-VLAN pool, not "S-tag" |

The S-tag the handler selected (`SVLAN-N-HSI`) is not in this result. An S-VLAN carries many services, so it is
*selected* (visible in the per-condition results from Step 4) but not claimed by one service the way a port and
a C-tag are.

Both rows must carry your intent ID. Resources that belonged to the initial inventory (`UNI-002-1`,
`CTAG-N-002`) show `existing-service-placeholder` instead; those were already in use before the demo.

To list every resource currently in use, whichever service owns it, drop the intent filter. This shows
more than your intent's allocation:

```sparql
PREFIX pon: <http://broadband-forum.org/ont/pon-resource#>
SELECT ?resource ?svc WHERE {
  GRAPH <http://tmforum.org/api/v5/resources> {
    ?resource pon:inUse true ; pon:assignedToService ?svc .
  }
}
```

On a clean store after one Fulfilled intent, expect **4 rows**:

| Resource | `svc` | Where it comes from |
|---|---|---|
| `UNI-002-1` | `existing-service-placeholder` | Already in use in the seed data (see Step 1b); not part of your intent |
| `CTAG-N-002` | `existing-service-placeholder` | Already in use in the seed data (C-tag 201 in the North pool); not part of your intent |
| a free `UNI-…` (e.g. `UNI-001-1`) | your intent ID | Allocated by this demo |
| a free `CTAG-…` (e.g. `CTAG-N-001`) | your intent ID | Allocated by this demo |

Each further Fulfilled intent adds two rows. Use the filtered query above to see only your own intent.

Each resource should appear **once**. If the same port or C-tag appears with two different service IDs,
two intents were allocated the same resource. The write-back only claims a resource that is still
`pon:inUse false`, so this should not happen; start from a clean store (`docker compose --profile access down -v`) and
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

Post the same observation a second time. This triggers another evaluation of an intent that already holds its
resources:

```bash
post_metric DownstreamBandwidthMetric 150.0

sleep 1

report
results
```

Expected: still **`Fulfilled`**, all rows `true`, and the three `DeliveryExpectationReport` rows still show the
**same** `target` resources as in Step 4 (`UNI-001-1`, `SVLAN-N-HSI`, `CTAG-N-001`), even though the UNI and
C-tag are now in use and no longer in the free pool.

Each observation creates a new IntentReport. Allocation does not repeat, and the intent does not choose again:

- Before each evaluation the handler looks up the resources already assigned to the intent
  (`HandlerStateWriter.heldResources()`) and the evaluator keeps them as the intent's choice (`held`).
- Flow 4 claims only what is still missing, so a repeat evaluation changes nothing, and an interrupted
  allocation is completed by the next evaluation.
- The intent stays Fulfilled even when other intents have used up the rest of the free pool, because it no
  longer depends on it.

The S-tag is not held: an S-VLAN is shared, so it is chosen again each time. For this demo one S-VLAN is chosen
irrespective of service provider (the lowest-named HSI S-VLAN); provider-aware selection is tracked as
`bbf921j-3or`.

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

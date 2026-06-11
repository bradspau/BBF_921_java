# HSI Intent Condition Validation

How to exercise and validate the five performance conditions in the BBF HSI Service Intent
via SPARQL against the `tmf921-eval` inference dataset and via the TMF921 API.

---

## The Five Conditions

| Condition | Predicate | Threshold | Rule type |
|-----------|-----------|-----------|-----------|
| Downstream bandwidth | `quan:greaterOrEqual` | 100 Mbps | `quan:quanatLeast` |
| Upstream bandwidth | `quan:greaterOrEqual` | 20 Mbps | `quan:quanatLeast` |
| Latency | `quan:smaller` | 25 ms | `quan:quansmaller` |
| Jitter | `quan:smaller` | 3 ms | `quan:quansmaller` |
| Packet loss | `quan:smaller` | 0.1 percent | `quan:quansmaller` |

---

## How the TIO Rules Fire

The `tmf921-eval` dataset runs a Jena `GenericRuleReasoner` over `tio_all.rules`.
The quantity comparison rules require a **typed blank node** pattern:

```
(?F rdf:type quan:quanatLeast)
(?F rdf:first ?Val)  (?F rdf:rest ?R)  (?R rdf:first ?Bound)
(?Val rdf:value ?V)  (?Bound rdf:value ?B)  ge(?V, ?B)
→ (?F rdf:value "true"^^xsd:boolean)
```

When `rdf:value "true"^^xsd:boolean` is present on a node typed `icm:PropertyExpectation`,
the `icmPropertyResult` rule further asserts `icm:result "true"^^xsd:boolean`.

### Namespace used by the rules

```
quan: <http://tio.models.tmforum.org/tio/v3.6.0/QuantityOntology/>
icm:  <http://tio.models.tmforum.org/tio/v3.6.0/IntentCommonModel/>
imo:  <http://tio.models.tmforum.org/tio/v3.6.0/IntentManagementOntology/>
```

### Known mismatch in the seed expression

The HSI seed intent (`seed_data/hsionlyintent_v0.5.ttl`) uses:

```turtle
@prefix quan: <https://tio.models.tmforum.org/QuantityModel/> .   # ← https, different path
bbf:HSI_DownstreamBandwidthCondition  a icm:Condition ;
    quan:greaterOrEqual ( bbf:DownstreamBandwidthMetric
      [ rdf:value "100"^^xsd:decimal ] ) .
```

This uses `quan:greaterOrEqual` as a **predicate**, not a typed blank node, and uses
a different namespace. The rules will not fire for this expression as-is.
To enable end-to-end compliance evaluation, the expression must be rewritten to use
the typed blank node pattern shown in the SPARQL examples below.

---

## Step-by-Step Validation via SPARQL

All SPARQL is run against `http://localhost:3030/tmf921-eval/sparql` (POST).
Clear the default graph before each test:

```sparql
CLEAR DEFAULT
```

### Step 1 — Post a condition with an observed value

Use the GSP endpoint to load Turtle into the eval default graph:

```
POST http://localhost:3030/tmf921-eval/data
Content-Type: text/turtle
```

**Example: downstream bandwidth in range (120 Mbps observed, 100 Mbps required)**

```turtle
@prefix quan: <http://tio.models.tmforum.org/tio/v3.6.0/QuantityOntology/> .
@prefix rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
@prefix xsd:  <http://www.w3.org/2001/XMLSchema#> .

<urn:test:cmp> a quan:quanatLeast ;
    rdf:first <urn:test:observed> ;
    rdf:rest  <urn:test:rest> .
<urn:test:rest> rdf:first <urn:test:bound> .
<urn:test:observed> rdf:value "120"^^xsd:decimal .
<urn:test:bound>    rdf:value "100"^^xsd:decimal .
```

**Example: downstream bandwidth out of range (80 Mbps observed)**

```turtle
@prefix quan: <http://tio.models.tmforum.org/tio/v3.6.0/QuantityOntology/> .
@prefix rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
@prefix xsd:  <http://www.w3.org/2001/XMLSchema#> .

<urn:test:cmp> a quan:quanatLeast ;
    rdf:first <urn:test:observed> ;
    rdf:rest  <urn:test:rest> .
<urn:test:rest> rdf:first <urn:test:bound> .
<urn:test:observed> rdf:value "80"^^xsd:decimal .
<urn:test:bound>    rdf:value "100"^^xsd:decimal .
```

### Step 2 — Query for inferred result

```sparql
ASK {
  <urn:test:cmp> <http://www.w3.org/1999/02/22-rdf-syntax-ns#value>
                 "true"^^<http://www.w3.org/2001/XMLSchema#boolean>
}
```

**In-range**: returns `true`  
**Out-of-range**: returns `false`

### Step 3 — Query `icm:result` (requires PropertyExpectation)

To test the full chain to `icm:result`, also type the node as `icm:PropertyExpectation`:

```turtle
<urn:test:cmp> a quan:quanatLeast ;
               a icm:PropertyExpectation ;
    rdf:first <urn:test:observed> ;
    rdf:rest  <urn:test:rest> .
<urn:test:rest> rdf:first <urn:test:bound> .
<urn:test:observed> rdf:value "120"^^xsd:decimal .
<urn:test:bound>    rdf:value "100"^^xsd:decimal .
```

Then query:

```sparql
PREFIX icm: <http://tio.models.tmforum.org/tio/v3.6.0/IntentCommonModel/>
ASK { <urn:test:cmp> icm:result "true"^^<http://www.w3.org/2001/XMLSchema#boolean> }
```

---

## Reference Values for All Five Conditions

| Condition | Rule type | Threshold | In-range example | Out-of-range example |
|-----------|-----------|-----------|-----------------|----------------------|
| Downstream BW | `quan:quanatLeast` | `"100"` | `"120"` | `"80"` |
| Upstream BW | `quan:quanatLeast` | `"20"` | `"25"` | `"15"` |
| Latency | `quan:quansmaller` | `"25"` | `"10"` | `"30"` |
| Jitter | `quan:quansmaller` | `"3"` | `"1"` | `"5"` |
| Packet loss | `quan:quansmaller` | `"0.1"` | `"0.05"` | `"0.2"` |

`quan:quanatLeast` fires when `observed >= bound` (ge built-in).  
`quan:quansmaller` fires when `observed < bound` (lt built-in).  
Boundary values: `quanatLeast` includes the bound (>=); `quansmaller` excludes it (strict <).

---

## Step-by-Step Validation via the API

### 1. Trigger evaluation

Send any PATCH to the HSI intent to fire `schedule_evaluation`:

```bash
curl -X PATCH \
  "http://localhost:8000/tmf-api/intentManagement/v5/intent/{HSI_ID}" \
  -H "Content-Type: application/merge-patch+json" \
  -d '{"description": "re-evaluate"}'
```

Replace `{HSI_ID}` with the UUID returned when the intent was seeded.

### 2. Wait for the background task

The evaluator runs as an `asyncio` background task. Wait ~1 second before querying.

### 3. Fetch the intent report

```bash
curl "http://localhost:8000/tmf-api/intentManagement/v5/intent/{HSI_ID}/intentReport"
```

The latest report will contain:

```json
{
  "intentHandlingState": "Degraded",
  "intentHandlingReason": "No intentHandlingState inferred from expression"
}
```

**Current expected result**: `Degraded` because the seed expression format is not compatible
with the TIO rules (see namespace mismatch note above).

**Expected result after fixing the expression format**: `Complies` when all observed metric
values satisfy the thresholds, `Degraded` otherwise (the `tioComplianceNegative` rule
is not yet present in `tio_all.rules` — absence of `imo:Complies` maps to `Degraded`
in `evaluator.py`).

### 4. Fetch a specific report by ID

```bash
curl "http://localhost:8000/tmf-api/intentManagement/v5/intent/{HSI_ID}/intentReport/{REPORT_ID}"
```

---

## Running the Automated Tests

```bash
# Requires docker compose up (live Fuseki with tmf921-eval)
pytest tests/integration/test_hsi_eval_conditions.py -v
```

Each test class covers one condition; each test method covers one value (in-range,
boundary, out-of-range). The `clear_eval` autouse fixture clears the eval default
graph before and after every test to prevent cross-test interference.

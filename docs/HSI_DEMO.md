# HSI Intent Demo — End-to-End Walkthrough (Java)

This guide exercises the BBF High-Speed Internet (HSI) service intent through the
TMF921 API under the standalone profile (no resource inventory). It verifies that:

1. The intent handler evaluates structural and metric conditions correctly
2. `intentHandlingState` cycles between `Degraded` and `Fulfilled` as metric observations change
3. Every evaluation produces a TR290 intent report, with an `icm:result` for each intent element
4. A ProbeIntent is auto-activated or terminated by its evaluation (Flow 1)
5. A Degraded intent gets a best-effort proposal (Flow 3)
6. All of the above behave identically on every evaluation engine (`java`, `jena`, `compare`)

> For the access domain demo with PON resource inventory and UNI/CTAG allocation,
> see [`Access_HSI_Demo.md`](Access_HSI_Demo.md).

---

## Architecture

```
POST /intent          →  TDB2 intent named graph
POST /observation     →  TDB2 observations graph  (per intent)
                                 ↓ merged at evaluation time
         EvaluationEngine (intent.evaluator: java | jena | compare)
                                 ↓
         result graph  →  IntentReport + TR290 report  +  handlerState graph  (TDB2)
```

The evaluator runs as a Spring `@Async` background task every time an intent is activated,
has its expression patched, or receives a new observation. It merges the expression Turtle
with the observation graph, then evaluates the TIO expression to produce `Fulfilled` or
`Degraded`. By default the Java evaluator (`TurtleEvaluator`) does this; see
[Choosing the evaluation engine](#choosing-the-evaluation-engine) to run the Jena rule engine instead.

---

## The handler's four post-evaluation flows

After every evaluation the handler creates the intent report, fires `IntentReportCreateEvent`, and then runs up
to four follow-up actions. They are called **Flow 1 to Flow 4** in the code (`EvaluationDispatcher`), in the
docs and in these demos; the names are this project's, not TM Forum terms. They run in the same background task
as the evaluation, and an error in one is logged and never affects the report.

| Flow | Runs when | What it does | Shown in |
|---|---|---|---|
| **1 — ProbeIntent** | a `ProbeIntent` in `ACKNOWLEDGED` has just been evaluated | moves it to `ACTIVE` if `Fulfilled`, `TERMINATED` if `Degraded` (a capability probe: "could this be delivered?") | **this demo**, Step 9 |
| **2 — Judge / Preference** | the evaluation is `Fulfilled` and the intent's `lifecycleStatus` is `DEGRADED` | moves it back to `ACTIVE` | [`Access_HSI_Demo.md`](Access_HSI_Demo.md) Step 11 |
| **3 — Best / Propose** | the evaluation is `Degraded` and the intent is an `Intent` in `ACKNOWLEDGED` or `ACTIVE` | rewrites each failed quantity bound in the stored expression to the observed value (a best-effort counter-proposal) and fires `IntentAttributeValueChangeEvent` | **this demo**, Step 10 |
| **4 — Resource allocation** | the evaluation is `Fulfilled` and the intent is not a `ProbeIntent` | writes `pon:inUse true` and `pon:assignedToService <intent>` to the resources the intent chose in the `resources` graph | [`Access_HSI_Demo.md`](Access_HSI_Demo.md) (this demo has no resource inventory, so it does nothing here) |

This demo shows **Flow 1** (Step 9) and **Flow 3** (Step 10). The access demo shows all four, with resources.

---

## The graphs this solution uses, and why

Everything is stored as RDF in one TDB2 dataset, split into **named graphs**. Most graphs are **per intent**: each
intent has its own set, identified by the intent's id, so one intent's observations, state and results never mix
with another's. This demo has no resource inventory, so there is no shared `resources` graph. Knowing which graph
holds what tells you where to look at each step of this demo (the steps query them through Fuseki).

| Graph (`http://tmforum.org/api/v5/…`) | One per | What it holds | Written by | Why it exists |
|---|---|---|---|---|
| `intents/{id}` | intent | The TMF921 `Intent` resource, including its expression (the Turtle, kept as an opaque string) and its lifecycle status. | `POST` / `PATCH /intent` | The source of truth for what the intent *asks for*. The evaluator reads the expression from here. |
| `intents/{id}/observations` | intent | The metric observations posted for that intent (metric, value, unit, time). | `POST /intent/{id}/observation` | Observations are the live measurements the conditions are checked against. They are kept apart from the expression and merged with it at evaluation time. |
| `intents/{id}/inferred` | intent | **The evaluation engine's answer**: an explicit `rdf:value true`/`false` on the intent, each expectation and each condition, an `icm:reason` on anything false because of an error or a missing observation, and a `met:observed` link to the observation each metric condition used. | The evaluation engine (java, jena or compare), after every evaluation | Both engines write **the same graph in the same vocabulary**, so everything downstream does not care which engine ran, and the two can be compared triple for triple (that is what `compare` mode does). It is replaced on every evaluation, so it always shows the latest answer. It does **not** contain `icm:result`: that belongs in the report (below). Browse it in Fuseki to see exactly what the engine decided. |
| `intents/{id}/handlerState` | intent | The handler's record of the latest evaluation: the handling state (`Fulfilled`/`Degraded`), when it was last evaluated, and one entry per condition (passed or not, observed value, bound). | The handler, after every evaluation | What the handler and the notifications built on it work from. Replaced on every evaluation. |
| `reports/{id}` | intent report | One `IntentReport` and, for an intent with an `icm:Intent`, the TR290 report beneath it: an `icm:result` for the intent, every expectation and every condition, what each was derived from (`icm:resultFrom`), and why it is false (`icm:reason`). It points back to its intent with `tmf:parentIntent`. | The handler, after every evaluation | The report is what the API returns (`GET …/intentReport`) and what `icm:result` is queried from. A **new** report graph is created on every evaluation, so reports accumulate (newest first) and you can see the intent change over time. The handler builds it from the expression plus the `inferred` result, copying each `icm:result` from the matching `rdf:value`; it never decides truth itself. |

Other shared graphs exist but are not used in this demo: `hubs` (notification subscriptions), `audit/{id}`
(immutable state-change records) and `ontology`.

How they fit together on one evaluation:

```
intents/{id} (expression) ┐
intents/{id}/observations ┴─ merged ─►  evaluation engine  ─►  intents/{id}/inferred   (the answer)
                                                                     │
                                                                     ├─►  reports/{new id}          (TR290 report, icm:result)
                                                                     └─►  intents/{id}/handlerState (state + per-condition results)
```

Which graph to look at:

| To see… | Look in |
|---|---|
| Whether each condition is met (`icm:result`), and why not | `reports/{id}`, via the `results` helper in Step 4 |
| What the engine itself decided, in raw form (and to check java, jena and compare agree) | `intents/{id}/inferred` |
| The handler's record of the latest state and per-condition results | `intents/{id}/handlerState` (Step 5) |

To list every graph and its size in Fuseki:

```sparql
SELECT ?g (COUNT(*) AS ?triples) WHERE { GRAPH ?g { ?s ?p ?o } } GROUP BY ?g ORDER BY ?g
```

---

## Prerequisites

### Start the standalone server, with Fuseki

Steps 3 to 5 query the graph store through Fuseki (SPARQL + web UI on port 3030), so start the server with
both the `standalone` and `fuseki` profiles. TDB2 data persists in `tdb2-data/` (or the Docker volume), so
start from a clean store for the expected results below.

**Docker (recommended — wipes volume on restart):**

```bash
docker compose --profile standalone down -v   # -v removes named volumes → clean TDB2 store (--profile is required)
APP_PROFILES=standalone,fuseki docker compose --profile standalone up --build
```

**Dev server:**

```bash
make clean         # removes tdb2-data/, tdb2-access/, tdb2-aggregation/
mvn spring-boot:run -Dspring-boot.run.profiles=standalone,fuseki
```

Wait for:
```
Start Fuseki (http=3030)
Started Application in N.NNN seconds
```

Fuseki is read-only; the dataset is `/intents`, the query endpoint is `http://localhost:3030/intents/sparql`, and
the web UI is <http://localhost:3030>.

> **A Fuseki `curl` prints nothing at all?** A working query always prints at least a header row, even with no
> matches. No output means Fuseki is not running: the server was started without the `fuseki` profile (plain
> `docker compose --profile standalone up` or `make run`). Port 3030 is still published by Docker, so `curl -s`
> just returns silently. Restart with the `APP_PROFILES=standalone,fuseki` command above.

### Verify (port 8000 for the standalone profile)

```bash
curl http://localhost:8000/health
```

Expected:
```json
{"status": "UP", "graph": "UP", "evaluator": {"engine": "java", "ready": true}}
```

> All steps below use port **8000**. Set once: `BASE=http://localhost:8000/tmf-api/intentManagement/v5`

### Stop the server

**Docker:**

```bash
docker compose --profile standalone down      # stop and remove the container; the data is kept
docker compose --profile standalone stop      # or only stop it; `start` resumes the same container
```

`--profile standalone` is required: the services are profile-gated, so without it Compose finds nothing to stop.

**Dev server:** press `Ctrl-C` in the terminal running `mvn spring-boot:run`.

### Optionally clear the data

There is no separate volume for observations. Observations are stored in the same TDB2 store as everything else
(intents, observations, reports, and the handler state and inferred results), and that store lives in one named
Docker volume, `tdb2-standalone`. Compose prefixes the volume name with the project name, so here it is
`bbf_921_java_tdb2-standalone`; `docker volume ls` shows it. Clearing the observations therefore means clearing the
store:

```bash
docker compose --profile standalone down -v            # stop, remove the container AND this profile's data volume
# or, once the container is stopped or removed:
docker volume rm bbf_921_java_tdb2-standalone
```

This removes every intent, observation and report. The next start begins with an empty store.

Dev server: stop it first, then `make clean` (removes `tdb2-data/`, `tdb2-access/` and `tdb2-aggregation/`).

Observations cannot be deleted one by one: the observation endpoint only accepts `POST`, and Fuseki is read-only. If
you only want a fresh set of observations, you do not have to wipe anything: observations belong to one intent, so
creating a new intent (Step 1) starts with none.

---

## Choosing the evaluation engine

There are **two evaluation engines** and one **mode** that uses both. The choice is `intent.evaluator`:

| `intent.evaluator` | What runs | Use it for |
|---|---|---|
| `java` (default) | The Java evaluator only (`TurtleEvaluator`). | Normal use. This is what you get when nothing is set. |
| `jena` | The Jena rule engine only: the same intent is evaluated by rules (`src/main/resources/rules/`). Needs the licensed ontology files in `ontology/`. | Running on the rule engine. |
| `compare` | **Both engines on every evaluation.** The handler uses Java's answer, so reports, state, flows and notifications are exactly as in `java` mode; the Jena answer is only compared with it. Any difference is logged as a `compare:` warning, and no warning means the two engines agree. | Checking that the rule engine agrees with Java on a scenario, such as this demo. Not for production: it does the work of both engines (about 15-45 ms extra per evaluation). |

The demo behaves the same on all three; the choice only changes who computes the result. The setting is read when
the application starts, so switch engine by restarting it. Spring maps the property to the environment variable
`INTENT_EVALUATOR`, and `docker-compose.yml` passes it through (default `java`).

**Java engine only (the default).** Set nothing, or set `java` explicitly:

```bash
# Docker
APP_PROFILES=standalone,fuseki docker compose --profile standalone up --build
INTENT_EVALUATOR=java APP_PROFILES=standalone,fuseki docker compose --profile standalone up --build    # same, explicit

# Dev server
mvn spring-boot:run -Dspring-boot.run.profiles=standalone,fuseki
mvn spring-boot:run -Dspring-boot.run.profiles=standalone,fuseki -Dspring-boot.run.arguments=--intent.evaluator=java    # same, explicit
```

**Jena engine only, or compare mode:**

```bash
# Docker
INTENT_EVALUATOR=jena    APP_PROFILES=standalone,fuseki docker compose --profile standalone up --build
INTENT_EVALUATOR=compare APP_PROFILES=standalone,fuseki docker compose --profile standalone up --build

# Dev server
mvn spring-boot:run -Dspring-boot.run.profiles=standalone,fuseki -Dspring-boot.run.arguments=--intent.evaluator=jena
INTENT_EVALUATOR=compare mvn spring-boot:run -Dspring-boot.run.profiles=standalone,fuseki
```

**Check which engine is running** (field `evaluator`):

```bash
curl http://localhost:8000/health
# {"status":"UP","graph":"UP","evaluator":{"engine":"java","ready":true}}
```

The Jena engine needs the licensed ontology files in `ontology/`; without them `jena` reports `"ready": false` (HTTP
503) and evaluations are skipped, and `compare` skips the Jena side (logging one `compare: secondary engine 'jena'
skipped, not ready` info line per evaluation) and returns Java's answer. In `compare` mode, a difference between
the engines is logged as a `WARN` line starting `compare:`; none means the engines agree on everything you ran:

```bash
docker compose --profile standalone logs | grep -E 'WARN.*compare:'
```

After an evaluation, the engine's result graph is in the named graph
`http://tmforum.org/api/v5/intents/{id}/inferred`, which you can browse in Fuseki.

---

## The intent expression

The HSI expression (`seed_data/hsi_demo_intent.ttl`) is an `icm:Intent` over four expectations. The structural
checks are satisfied by facts asserted inside the expression itself, so they hold from the start; only the
performance conditions depend on posted observations:

```
┌─ bbf:HSIIntent  log:allOf ──────────────────────────────────────────┐
│    DeliveryCheck, UNICheck, PerformanceCheck, HSIMonitoringExpectation│
│                                                                      │
│  icm:DeliveryExpectation  bbf:DeliveryCheck                          │
│    icm:target          bbf:SelectedUNIInterface                      │
│    icm:deliveryType    bbf:HSIService                                │
│    (the target already has a member typed bbf:HSIService)            │
│                                                                      │
│  icm:PropertyExpectation  bbf:UNICheck   log:allOf                   │
│    log:match ( SelectedUNIInterface operationalState OperationalUp ) │
│    log:match ( SelectedUNIInterface provisioningState Ready )        │
│                                                                      │
│  icm:PropertyExpectation  bbf:PerformanceCheck   log:allOf           │
│    quan:atLeast ( DownstreamBandwidthMetric 100 Mbps )               │
│    quan:atLeast ( UpstreamBandwidthMetric 20 Mbps )                  │
│    quan:smaller ( LatencyMetric 25 ms )                              │
│    quan:smaller ( JitterMetric 3 ms )                                │
│    quan:smaller ( PacketLossMetric 0.1 percent )                     │
│                                                                      │
│  icm:ReportingExpectation  bbf:HSIMonitoringExpectation              │
│    icm:target  bbf:HSIMonitoringTarget (lists every element to report)│
└─────────────────────────────────────────────────────────────────────
```

**Structural conditions** (the delivery expectation and the two `log:match` UNI checks) depend on facts
asserted inside the expression Turtle — they cannot be satisfied by metric observations.

**Metric conditions** (the five quantity conditions) require `met:Observation` records posted via
`POST /intent/{id}/observation`. Observations are quantities (TR292D): a number and a unit. The conditions state
their bounds with units (`100 Mbps`, `25 ms`, `0.1 percent`), so each observation is posted with its unit. Unit
prefixes are converted (`0.15 Gbps` satisfies `>= 100 Mbps`); a value in a different dimension, or without a
unit, cannot be compared and the condition is `false` with a "unit mismatch" reason.

**Reporting.** The `HSIMonitoringExpectation` decides which elements are reported on (TR290 §4.2): the report
contains one entry per element listed in its target, so every element we want an `icm:result` for is listed there.

---

## Step 1 — Create the HSI intent

Set `BASE` and create the intent from the expression file:

```bash
BASE=http://localhost:8000/tmf-api/intentManagement/v5

HSI_INTENT=$(curl -s -X POST "$BASE/intent" \
  -H "Content-Type: application/json" \
  -d "{
    \"@type\": \"Intent\",
    \"name\": \"BBF HSI Service Demo\",
    \"expression\": {
      \"@type\": \"TurtleExpression\",
      \"iri\": \"http://broadband-forum.org/Intent#HSIIntent\",
      \"expressionValue\": $(python3 -c "import sys, json; print(json.dumps(open('seed_data/hsi_demo_intent.ttl').read()))")
    }
  }")

echo "$HSI_INTENT" | python3 -m json.tool
INTENT_ID=$(echo "$HSI_INTENT" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")
echo "Intent ID: $INTENT_ID"
```

The API returns `201 Created` with `lifecycleStatus: ACKNOWLEDGED`, and the first evaluation runs in the
background. (The command reads `seed_data/hsi_demo_intent.ttl`, so run it from the repository root.)

---

## Step 2 — Activate the intent

```bash
curl -s -X PATCH "$BASE/intent/$INTENT_ID" \
  -H "Content-Type: application/json" \
  -d '{"lifecycleStatus": "ACTIVE"}' | python3 -m json.tool
```

Expected: `"lifecycleStatus": "ACTIVE"`. The activation triggers another evaluation cycle.

---

## Step 3 — Check the initial intent report

Before posting observations, read the intent's reports to see its starting state. A new report is created every
time the intent is evaluated, and the list is newest first:

```bash
sleep 1
curl -s "$BASE/intent/$INTENT_ID/intentReport" | python3 -c "
import sys, json
for r in json.load(sys.stdin):
    print(r['creationDate'], r['intentHandlingState'])
    print('   ', r['intentHandlingReason'])
"
```

The initial report shows **`Degraded`** because no metric observations exist yet, and the reason lists the five
metrics with no observation. There are two reports so far: one from creation and one from activation.

Each report also carries its detail in `expression`. That is a `TurtleExpression` whose `expressionValue` is the
TM Forum TR290 intent report: a report for the intent, for each expectation and for each condition, with the
truth value of each (`icm:result`), what it was derived from (`icm:resultFrom`), and why it is false
(`icm:reason`). To read the newest one:

```bash
curl -s "$BASE/intent/$INTENT_ID/intentReport" \
  | python3 -c "import sys, json; print(json.load(sys.stdin)[0]['expression']['expressionValue'])"
```

It is long (one entry per intent element), so the steps below query it in Fuseki instead (the `results` helper in
Step 4), which shows one row per element.

**Pretty-print the full report.** The raw Turtle above is hard to read: long URIs and no obvious order. This
prints the whole newest report as a tree, from the report's own fields down to the intent, its expectations and
their conditions, with the `icm:result` of every element and the reason for each false one:

```bash
pip install rdflib    # once; only this snippet needs it

curl -s "$BASE/intent/$INTENT_ID/intentReport" | python3 -c '
import sys, json
from rdflib import Graph, Namespace, RDF, RDFS

ICM = Namespace("http://tio.models.tmforum.org/tio/v3.6.0/IntentCommonModel/")
short = lambda u: str(u).rsplit("#", 1)[-1].rsplit("/", 1)[-1]

report = json.load(sys.stdin)[0]            # newest report
print("IntentReport", report["id"])
for k in ("creationDate", "intentId", "intentHandlingState", "intentHandlingReason"):
    print(f"  {k + chr(58):22}{report.get(k)}")

g = Graph().parse(data=report["expression"]["expressionValue"], format="turtle")

def show(node, depth):
    pad = "    " * depth
    kind = short(next(g.objects(node, RDF.type), "?"))
    about = short(next(g.objects(node, ICM.about), "?"))
    result = next(g.objects(node, ICM.result), "?")
    print(f"{pad}{kind:28} {about:38} result={str(result).lower()}")
    for reason in g.objects(node, ICM.reason):
        print(f"{pad}  · reason: {g.value(reason, RDFS.label)}")
    for tr in g.objects(node, ICM.targetReport):
        members = ", ".join(sorted(short(m) for m in g.objects(tr, RDFS.member))) or "(none)"
        print(f"{pad}  · target {short(next(g.objects(tr, ICM.about), chr(63)))}: {members}")
    for child in sorted(g.objects(node, ICM.resultFrom), key=short):
        show(child, depth + 1)

print()
for root in g.subjects(RDF.type, ICM.IntentReport):
    show(root, 0)
'
```

Expected for the initial report: the intent is `false`, the delivery, UNI and monitoring expectations are
`true`, and the performance expectation is `false` because all five of its conditions are `false` with a
`no observation` reason:

```
IntentReport 0b1c…
  creationDate:         2026-10-19T08:19:04.177794517Z
  intentId:             8c39…
  intentHandlingState:  Degraded
  intentHandlingReason: Conditions not met: atLeast: no observation: DownstreamBandwidthMetric; … PacketLossMetric

IntentReport                 HSIIntent                              result=false
    DeliveryExpectationReport    DeliveryCheck                          result=true
      · target SelectedUNIInterface: HSIServiceInstance
    ReportingExpectationReport   HSIMonitoringExpectation               result=true
    PropertyExpectationReport    PerformanceCheck                       result=false
      · target HSIServiceTarget: HSIServiceInstance
        ConditionReport              DL_Check                               result=false
          · reason: no observation: DownstreamBandwidthMetric
        ConditionReport              Jit_Check                              result=false
          · reason: no observation: JitterMetric
        ConditionReport              Lat_Check                              result=false
          · reason: no observation: LatencyMetric
        ConditionReport              PL_Check                               result=false
          · reason: no observation: PacketLossMetric
        ConditionReport              UL_Check                               result=false
          · reason: no observation: UpstreamBandwidthMetric
    PropertyExpectationReport    UNICheck                               result=true
      · target SelectedUNIInterface: HSIServiceInstance
        ConditionReport              UNIReadyCondition                      result=true
        ConditionReport              UNIUpCondition                         result=true
```

Run the same snippet after any later step to see the report change; it always shows the newest one. (To see an
older report, change `[0]` to a later index: the list is newest first.)

### See the intent and its reports in Fuseki

The intent and every report are also stored as RDF, and with the `fuseki` profile you can look at them directly
(see "The graphs this solution uses" above for what each graph is). Either open the Fuseki UI at
<http://localhost:3030>, choose the dataset `/intents`, and paste a query below into the **query** tab (replace
`$INTENT_ID` with your intent's id, `echo "$INTENT_ID"`), or run it from the shell with this helper:

```bash
sparql() { curl -s -G http://localhost:3030/intents/sparql -H 'Accept: text/csv' --data-urlencode "query=$1"; }
```

**Which graphs does this intent have?** The intent itself, the handler's state and the engine's inferred result.
(A fourth, `…/observations`, appears in Step 4 when the first observation is posted.)

```bash
sparql "SELECT ?g (COUNT(*) AS ?triples) WHERE { GRAPH ?g { ?s ?p ?o }
        FILTER (CONTAINS(STR(?g), \"$INTENT_ID\")) } GROUP BY ?g ORDER BY ?g"
```

**The intent resource** (id, name, lifecycle status, the IRI of its expression; the expression text itself is a
long string, so it is left out here):

```bash
sparql "SELECT ?p ?o WHERE { GRAPH <http://tmforum.org/api/v5/intents/$INTENT_ID> { ?s ?p ?o }
        FILTER (STRLEN(STR(?o)) < 200) } ORDER BY ?p"
```

**Its reports, newest first.** Each report is its own named graph, linked to the intent by `tmf:parentIntent`. Right
after Steps 1 and 2 there are two, both `Degraded`:

```bash
sparql "PREFIX tmf: <http://tmforum.org/api/v5/>
PREFIX dcterms: <http://purl.org/dc/terms/>
SELECT ?report ?created ?state WHERE {
  GRAPH ?report { ?report tmf:parentIntent <http://tmforum.org/api/v5/intents/$INTENT_ID> ;
                          dcterms:created ?created .
                  OPTIONAL { ?report tmf:intentHandlingState ?state } }
} ORDER BY DESC(?created)"
```

**The engine's own answer**, before it was turned into a report, is in the `…/inferred` graph:

```bash
sparql "SELECT ?s ?p ?o WHERE { GRAPH <http://tmforum.org/api/v5/intents/$INTENT_ID/inferred> { ?s ?p ?o } }
        ORDER BY ?s ?p"
```

Expected on the initial state: `rdf:value false` for `HSIIntent`, `PerformanceCheck` and the five performance
conditions, `rdf:value true` for the delivery, UNI and monitoring expectations and the two UNI conditions, and an
`icm:reason` ("no observation: …") on each performance condition. There is no `icm:result` here: that is in the
report graph, which the `results` helper in Step 4 reads.

---

## Step 4 — Show the intent is not Fulfilled until every condition is met

The intent has **five** metric conditions (and three structural ones that already pass), all of which must pass:

| # | Condition | Type | Passes when |
|---|---|---|---|
| 1 | Delivery (`DeliveryCheck`) | `DeliveryExpectation` | the target has a member typed `bbf:HSIService` (asserted inline) |
| 2 | UNI up / ready (`UNICheck`) | two `log:match` | `operationalState` and `provisioningState` are asserted inline |
| 3 | Downstream bandwidth | `atLeast` | observed ≥ 100 Mbps |
| 4 | Upstream bandwidth | `atLeast` | observed ≥ 20 Mbps |
| 5 | Latency | `smaller` | observed < 25 ms |
| 6 | Jitter | `smaller` | observed < 3 ms |
| 7 | Packet loss | `smaller` | observed < 0.1 % |

The structural conditions (1 and 2) pass as soon as the intent is evaluated. Conditions 3–7 each need a metric
observation. You will post the metrics in two halves and check the intent report and the per-condition results
after each.

| Metric | URI |
|---|---|
| Downstream bandwidth | `http://broadband-forum.org/Intent#DownstreamBandwidthMetric` |
| Upstream bandwidth | `http://broadband-forum.org/Intent#UpstreamBandwidthMetric` |
| Latency | `http://broadband-forum.org/Intent#LatencyMetric` |
| Jitter | `http://broadband-forum.org/Intent#JitterMetric` |
| Packet loss | `http://broadband-forum.org/Intent#PacketLossMetric` |

Set up the variables and helpers once. `results` reads the TR290 intent report the handler stores with every
IntentReport: an `icm:result` for the intent, each expectation and each condition. It queries Fuseki, so it needs
the Fuseki profile (`standalone,fuseki`). All steps must run in the **same shell session** so that `$INTENT_ID`
is in scope (to resume in a new terminal: `INTENT_ID="<id from step 1>"`):

```bash
OBS="$BASE/intent/$INTENT_ID/observation"
BBF="http://broadband-forum.org/Intent#"

post_metric() {   # usage: post_metric DownstreamBandwidthMetric 150.0 Mbps
  curl -s -o /dev/null -X POST "$OBS" -H "Content-Type: application/json" \
    -d "{\"metricUri\": \"${BBF}$1\", \"value\": $2, \"unit\": \"$3\"}"
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
  | sed 's|http://tio.models.tmforum.org/tio/v3.6.0/IntentCommonModel/||; s|http://broadband-forum.org/Intent#||g'
}
```

This block only defines the variables and the three helpers, so it prints nothing.

> **How the intent is reported on (TR290 §4).** Each evaluation creates a TR290 report with one report per intent
> element: an `icm:IntentReport`, an expectation report per expectation (`icm:DeliveryExpectationReport`,
> `icm:PropertyExpectationReport`, ...) and an `icm:ConditionReport` per condition. Each says which element it
> covers (`icm:about`), its truth value (`icm:result`; for the intent and expectations `true` means compliant), and
> which reports it was derived from (`icm:resultFrom`), so the intent's result is built up from its conditions.
> A report that is `false` carries an `icm:reason`. A report for an expectation also names its target's members
> (`icm:targetReport`). The reporting expectation in the intent decides which elements are reported (TR290 §4.2).
> Observed values are left out because it is an `icm:ReportingExpectation`; an `icm:ObservationReportingExpectation`
> would add them (`met:observed`).

### 4a — First half: bandwidth metrics only

```bash
post_metric DownstreamBandwidthMetric 150.0 Mbps    # >= 100 Mbps: pass
post_metric UpstreamBandwidthMetric   30.0  Mbps    # >= 20 Mbps:  pass
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
| `ConditionReport` | `DL_Check`, `UL_Check` | `true` | |
| `ConditionReport` | `Lat_Check`, `Jit_Check`, `PL_Check` | `false` | `reason` = `no observation: …` |
| `ConditionReport` | `UNIUpCondition`, `UNIReadyCondition` | `true` | the two `log:match` checks |
| `DeliveryExpectationReport` | `DeliveryCheck` | `true` | `target` = `HSIServiceInstance`, the member already in the target |
| `PropertyExpectationReport` | `UNICheck` | `true` | `target` = `HSIServiceInstance` |
| `PropertyExpectationReport` | `PerformanceCheck` | `false` | `target` = `HSIServiceInstance`; five conditions, three of them false |
| `ReportingExpectationReport` | `HSIMonitoringExpectation` | `true` | nothing to fail |
| `IntentReport` | `HSIIntent` | `false` | not compliant |

### 4b — Second half: latency, jitter and packet loss

```bash
post_metric LatencyMetric      8.0  ms         # < 25 ms: pass
post_metric JitterMetric       1.2  ms         # < 3 ms:  pass
sleep 2
report
results
```

Expected: still **`Degraded`**. Only `PacketLossMetric` is left in the reason. In `results`, four of the five
performance `ConditionReport` rows are now `true` (only `PL_Check` is `false`, with its reason), and
`PerformanceCheck` and the intent are still `false`.

```bash
post_metric PacketLossMetric   0.02 percent    # < 0.1 percent: pass
echo "Waiting for evaluation..."
sleep 2
report
results
```

Expected: **`Fulfilled`** with reason `None`, and all 12 rows `result = true`, up to and including the
`IntentReport`. The intent became compliant only on the last observation, when the final condition passed.

---

## Step 5 — Verify Fulfilled state

```bash
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

The handler's record of this evaluation, with the result of every condition, is in the `handlerState` graph:

```bash
curl -s -G http://localhost:3030/intents/sparql -H 'Accept: text/csv' \
  --data-urlencode "query=PREFIX imo: <http://tio.models.tmforum.org/tio/v3.6.0/IntentManagementOntology/>
SELECT ?state ?condition ?passed ?observed ?bound WHERE {
  GRAPH <http://tmforum.org/api/v5/intents/$INTENT_ID/handlerState> {
    <http://tmforum.org/api/v5/intents/$INTENT_ID> imo:intentHandlingState ?state ;
                                                   imo:hasConditionResult ?c .
    ?c a ?condition ; imo:conditionPassed ?passed .
    OPTIONAL { ?c imo:observedValue ?observed }
    OPTIONAL { ?c imo:boundValue ?bound }
  }
} ORDER BY ?condition ?bound" \
  | sed 's|http://tio.models.tmforum.org/tio/v3.6.0/[A-Za-z]*/||g'
```

Expected: eight rows, one per evaluated condition, all `passed = true` and `state = Fulfilled`: the delivery
expectation (`DeliveryExpectation`), the two `logMatch` checks, and the five metric conditions with their
observed value against the bound (type `atLeast`: `150.0` against `100` and `30.0` against `20`; type `smaller`:
`8.0` against `25`, `1.2` against `3` and `0.02` against `0.1`).

The engine's own answer is in the `inferred` graph (see Step 3). It now also holds a `met:observed` link from each
performance condition to the observation it used. The `…/observations` graph exists too, with one observation
per metric (metric, value, unit and `obtainedAt`); five so far:

```bash
sparql "SELECT ?s ?p ?o WHERE { GRAPH <http://tmforum.org/api/v5/intents/$INTENT_ID/observations> { ?s ?p ?o } }
        ORDER BY ?s ?p"
```

---

## Step 6 — Violate one condition (latency spike)

```bash
post_metric LatencyMetric 40.0 ms     # < 25 ms: VIOLATED
sleep 2
report
results | grep -E "Lat_Check|PerformanceCheck|IntentReport"
```

Expected: **`Degraded`**. Only the failing condition appears in `intentHandlingReason`
(`40.0 ms < 25 ms: FAIL`). In `results`, `Lat_Check` is `false` with the reason `40.0 ms < 25 ms is false`, and
`PerformanceCheck` and the intent are `false` because of it; every other row stays `true`.

---

## Step 7 — Self-heal

```bash
post_metric LatencyMetric 12.0 ms     # back within the < 25 ms threshold
sleep 2
report
```

Expected: **`Fulfilled`** — the system self-heals as soon as a within-threshold observation arrives. The newest
observation for a metric is the one that counts.

---

## Step 8 — Force re-evaluation by patching the expression

An evaluation runs when the intent is activated, when an observation is posted, and when the **expression** of an
active intent is patched. A PATCH of any other field (a description, for example) does not evaluate. To force a
re-evaluation without new data, send the expression again:

```bash
python3 -c "import json; print(json.dumps({'expression': {
  '@type': 'TurtleExpression',
  'iri': 'http://broadband-forum.org/Intent#HSIIntent',
  'expressionValue': open('seed_data/hsi_demo_intent.ttl').read()}}))" > /tmp/hsi-patch.json

curl -s -X PATCH "$BASE/intent/$INTENT_ID" \
  -H "Content-Type: application/json" \
  -d @/tmp/hsi-patch.json > /dev/null

sleep 2
report
```

Expected: one more report than before (the count in `reports so far` goes up by one) and the state is
unchanged, `Fulfilled`: the evaluation uses the current observations.

---

## Step 9 — Flow 1: ProbeIntent (capability probe)

A ProbeIntent auto-transitions after its first evaluation:
- `Fulfilled` → `ACTIVE`
- `Degraded` → `TERMINATED`

### 9a — Probe that passes

```bash
PROBE_PASS=$(curl -s -X POST "$BASE/intent" \
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

sleep 2
curl -s "$BASE/intent/$PROBE_ID" \
  | python3 -c "import sys,json; print('Status:', json.load(sys.stdin)['lifecycleStatus'])"
```

Expected: **`ACTIVE`**

### 9b — Probe that fails

```bash
PROBE_FAIL=$(curl -s -X POST "$BASE/intent" \
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

sleep 2
curl -s "$BASE/intent/$PROBE_FAIL_ID" \
  | python3 -c "import sys,json; print('Status:', json.load(sys.stdin)['lifecycleStatus'])"
```

Expected: **`TERMINATED`**

---

## Step 10 — Flow 3: Best/Propose

When an `ACKNOWLEDGED` or `ACTIVE` intent evaluates as `Degraded`, the handler:
1. Substitutes the observed value as the new bound for each failed condition
2. Writes the updated Turtle back as the intent's `expressionValue`
3. Fires `IntentAttributeValueChangeEvent`

The substitution does not re-evaluate by itself: the new bound is used by the **next** evaluation. An intent that
is not `ACTIVE` is not evaluated when an observation arrives, so the observation is posted first and the
evaluation is triggered by activating the intent.

### 10a — Create an intent with tight bounds

```bash
STRICT=$(curl -s -X POST "$BASE/intent" \
  -H "Content-Type: application/json" \
  -d '{
    "@type": "Intent",
    "name": "Flow3 Demo — tight bounds",
    "expression": {
      "@type": "TurtleExpression",
      "expressionValue": "@prefix bbf: <http://broadband-forum.org/Intent#> .\n@prefix quan: <http://tio.models.tmforum.org/tio/v3.6.0/QuantityOntology/> .\n@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .\n\nbbf:DL_Check  quan:atLeast ( bbf:DownstreamBandwidthMetric\n              [ rdf:value \"500\"^^xsd:decimal ; quan:unit \"Mbps\" ] ) .\n"
    }
  }')

STRICT_ID=$(echo "$STRICT" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")
echo "Strict intent: $STRICT_ID"
```

### 10b — Submit an observation, then activate

```bash
# System can only deliver 150 Mbps — below the 500 Mbps bound
curl -s -o /dev/null -X POST "$BASE/intent/$STRICT_ID/observation" \
  -H "Content-Type: application/json" \
  -d "{\"metricUri\": \"http://broadband-forum.org/Intent#DownstreamBandwidthMetric\", \"value\": 150.0, \"unit\": \"Mbps\"}"

curl -s -X PATCH "$BASE/intent/$STRICT_ID" \
  -H "Content-Type: application/json" \
  -d '{"lifecycleStatus": "ACTIVE"}' > /dev/null

sleep 3
```

### 10c — Inspect the proposal

```bash
curl -s "$BASE/intent/$STRICT_ID" \
  | python3 -c "
import sys, json
d = json.load(sys.stdin)
expr = d.get('expression', {}).get('expressionValue', '')
print('lifecycleStatus:', d.get('lifecycleStatus'))
print()
print('expressionValue (bound should now be 150, not 500):')
print(expr)
"
```

The bound `500` is replaced by the observed value `150.0`; the unit `Mbps` is kept.

```bash
curl -s "$BASE/intent/$STRICT_ID/intentReport" \
  | python3 -c "import sys,json; r=json.load(sys.stdin); print(r[0]['intentHandlingState'], '|', r[0]['intentHandlingReason'])"
```

Expected: the latest report is still **`Degraded`** (`150.0 Mbps >= 500 Mbps: FAIL`) because it was produced with the
old bound.

### 10d — The new bound takes effect

```bash
curl -s -o /dev/null -X POST "$BASE/intent/$STRICT_ID/observation" \
  -H "Content-Type: application/json" \
  -d "{\"metricUri\": \"http://broadband-forum.org/Intent#DownstreamBandwidthMetric\", \"value\": 150.0, \"unit\": \"Mbps\"}"

sleep 3
curl -s "$BASE/intent/$STRICT_ID/intentReport" \
  | python3 -c "import sys,json; r=json.load(sys.stdin); print('reports:', len(r), '| latest:', r[0]['intentHandlingState'])"
```

Expected: **`Fulfilled`** — the next evaluation uses the updated bound (`150.0`).

---

## Condition reference

| Condition | Element | Type | Threshold | Pass example | Fail example |
|---|---|---|---|---|---|
| Downstream BW | `DL_Check` | `quan:atLeast` | ≥ 100 Mbps | 150 | 80 |
| Upstream BW | `UL_Check` | `quan:atLeast` | ≥ 20 Mbps | 30 | 15 |
| Latency | `Lat_Check` | `quan:smaller` | < 25 ms | 8 | 40 |
| Jitter | `Jit_Check` | `quan:smaller` | < 3 ms | 1.2 | 5 |
| Packet loss | `PL_Check` | `quan:smaller` | < 0.1 percent | 0.02 | 0.2 |
| UNI operational | `UNIUpCondition` | `log:match` | operationalState = OperationalUp | asserted inline | absent |
| UNI ready | `UNIReadyCondition` | `log:match` | provisioningState = Ready | asserted inline | absent |
| Service delivery | `DeliveryCheck` | `icm:DeliveryExpectation` | target has an `HSIService` member | `rdfs:member` inline | absent |

---

## Code locations (Java)

| Class | What it does |
|---|---|
| `EngineConfig`, `JavaEvaluationEngine`, `JenaEvaluationEngine`, `ComparingEngine` | Select and run the evaluation engine (`intent.evaluator`) |
| `TurtleEvaluator.evaluate()` | Java engine: parses Turtle, runs the evaluation pipeline, walks the TIO tree |
| `TurtleEvaluator.resolveMetricRefs()` | Java engine: injects the latest `met:Observation` by `obtainedAt` |
| `TurtleEvaluator.evalDeliveryExpectation()` | Java engine: checks `icm:target` container membership |
| `TurtleEvaluator.evalMatch()` | Java engine: checks a `(s, p, o)` triple exists |
| `rules/tio_a_quantity.rules`, `tio_a_logic.rules`, `tio_b_logic.rules`, `tio_b_delivery.rules` | Jena engine: the same quantity, `log:match`, logical-operator and delivery logic as rules |
| `ResultGraphWriter`, `ContractExtractor`, `InferredGraphStore` | Build and store the result graph (`…/inferred`) |
| `ReportBuilder`, `ResultAdapter` | Turn either engine's result graph into the TR290 report and the handler result |
| `HandlerStateWriter` | Writes the `handlerState` graph |
| `EvaluationDispatcher` | Runs evaluations asynchronously and the post-evaluation flows (Flow 1 probe, Flow 3 best/propose) |

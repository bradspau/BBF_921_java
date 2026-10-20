# Access Domain HSI Demo — End-to-End Walkthrough (Java)

This guide exercises the BBF access-domain resource allocation flow using the
TMF921 API under the `access` Spring profile. It verifies that:

1. PON resource inventory loads at startup
2. Set-constructor conditions (`set:resourcesOfType`, `set:resourcesWithPropertyObject`) resolve correctly
3. A Fulfilled evaluation triggers Flow 4 resource write-back (`pon:inUse true`, `pon:assignedToService`)
4. Idempotent re-evaluation does not double-allocate
5. A ProbeIntent checks availability without allocating (Flow 1)
6. A `DEGRADED` intent recovers when it is `Fulfilled` again (Flow 2)
7. A too-tight bound is relaxed to what the network can deliver, only on the condition that failed (Flow 3)

> For the simpler metric-only HSI demo (no resource inventory), see [`HSI_DEMO.md`](HSI_DEMO.md).

---

## The handler's four post-evaluation flows

After every evaluation the handler creates the intent report, fires `IntentReportCreateEvent`, and then runs up
to four follow-up actions. They are called **Flow 1 to Flow 4** in the code (`EvaluationDispatcher`), in the
docs and in these demos; the names are this project's, not TM Forum terms. They run in the same background task
as the evaluation, and an error in one is logged and never affects the report.

| Flow | Runs when | What it does | Shown in |
|---|---|---|---|
| **1 — ProbeIntent** | a `ProbeIntent` in `ACKNOWLEDGED` has just been evaluated | moves it to `ACTIVE` if `Fulfilled`, `TERMINATED` if `Degraded` (a capability probe: "could this be delivered?") | **this demo**, Step 10 (and [`HSI_DEMO.md`](HSI_DEMO.md) Step 9) |
| **2 — Judge / Preference** | the evaluation is `Fulfilled` and the intent's `lifecycleStatus` is `DEGRADED` | moves it back to `ACTIVE` | **this demo**, Step 11 |
| **3 — Best / Propose** | the evaluation is `Degraded` and the intent is an `Intent` in `ACKNOWLEDGED` or `ACTIVE` | rewrites each failed quantity bound in the stored expression to the observed value (a best-effort counter-proposal) and fires `IntentAttributeValueChangeEvent` | **this demo**, Step 12 (and [`HSI_DEMO.md`](HSI_DEMO.md) Step 10) |
| **4 — Resource allocation** | the evaluation is `Fulfilled` and the intent is not a `ProbeIntent` | writes `pon:inUse true` and `pon:assignedToService <intent>` to the resources the intent chose (UNI port, C-tag) in the `resources` graph. It claims only what is still missing, so a repeat evaluation changes nothing. | **this demo**, Steps 6 to 8 |

This demo is about **Flow 4**: it is why the intent, once `Fulfilled`, holds a port and a C-tag that no other
intent can be given (Steps 6 to 8). Steps 10 to 12 then show the other three flows in the access domain, and how
they interact with Flow 4: a probe takes nothing, a recovered intent keeps what it holds, and a relaxed bound
leads to an allocation.

---

## The graphs this solution uses, and why

Everything is stored as RDF in one TDB2 dataset, split into **named graphs**. Most graphs are **per intent**: each
intent has its own set, identified by the intent's id, so one intent's observations, state and results never mix
with another's. A few graphs are shared. Knowing which graph holds what tells you where to look at each step of
this demo (the steps query them through Fuseki).

| Graph (`http://tmforum.org/api/v5/…`) | One per | What it holds | Written by | Why it exists |
|---|---|---|---|---|
| `intents/{id}` | intent | The TMF921 `Intent` resource, including its expression (the Turtle, kept as an opaque string) and its lifecycle status. | `POST` / `PATCH /intent` | The source of truth for what the intent *asks for*. The evaluator reads the expression from here. |
| `intents/{id}/observations` | intent | The metric observations posted for that intent (metric, value, unit, time). | `POST /intent/{id}/observation` | Observations are the live measurements the conditions are checked against. They are kept apart from the expression and merged with it at evaluation time. |
| `intents/{id}/inferred` | intent | **The evaluation engine's answer**: an explicit `rdf:value true`/`false` on the intent, each expectation and each condition, the resources chosen for each delivery expectation (as `rdfs:member` of the target), an `icm:reason` on anything false because of an error or a missing observation, and a `met:observed` link to the observation each metric condition used. | The evaluation engine (java, jena or compare), after every evaluation | Both engines write **the same graph in the same vocabulary**, so everything downstream does not care which engine ran, and the two can be compared triple for triple (that is what `compare` mode does). It is replaced on every evaluation, so it always shows the latest answer. It does **not** contain `icm:result`: that belongs in the report (below). Browse it in Fuseki to see exactly what the engine decided. |
| `intents/{id}/handlerState` | intent | The handler's working memory: the intent's handling state (`Fulfilled`/`Degraded`), when it was last evaluated, and one entry per condition (passed or not, observed value, bound, or the resource selected). | The handler, after every evaluation | The handler's record of the latest evaluation, one graph per intent, replaced on every evaluation. It is what the handler (and the notifications built on it) work from; it is not what keeps a choice stable. A resource chosen earlier is *held* because Flow 4 marked it in the `resources` graph (`pon:assignedToService`), and it is kept on re-evaluation instead of being chosen again (Step 7). |
| `reports/{id}` | intent report | One `IntentReport` and, for an intent with an `icm:Intent`, the TR290 report beneath it: an `icm:result` for the intent, every expectation and every condition, what each was derived from (`icm:resultFrom`), why it is false (`icm:reason`), and the resources chosen (`icm:targetReport`). It points back to its intent with `tmf:parentIntent`. | The handler, after every evaluation | The report is what the API returns (`GET …/intentReport`) and what `icm:result` is queried from. A **new** report graph is created on every evaluation, so reports accumulate (newest first) and you can see the intent change over time. The handler builds it from the expression plus the `inferred` result, copying each `icm:result` from the matching `rdf:value`; it never decides truth itself. |
| `resources` | shared | The PON resource inventory: ONTs, UNI ports, S-VLAN and C-tag allocations and their state (`pon:inUse`, `pon:operationalState`, …). | Loaded at startup from `BBF_access/`; updated by Flow 4 | The access domain's real-world state. The set constructors in the intent (`set:resourcesOfType`, `set:resourcesWithPropertyObject`, …) are resolved against it, and a Fulfilled evaluation writes `pon:inUse true` and `pon:assignedToService` back to it (Flow 4, Step 6). |

Other shared graphs exist but are not used in this demo: `hubs` (notification subscriptions), `audit/{id}`
(immutable state-change records) and `ontology`.

How they fit together on one evaluation:

```
intents/{id} (expression) ┐
intents/{id}/observations ├─ merged ─►  evaluation engine  ─►  intents/{id}/inferred   (the answer)
resources (inventory)     ┘                                          │
                                                                     ├─►  reports/{new id}        (TR290 report, icm:result)
                                                                     ├─►  intents/{id}/handlerState (state + per-condition results)
                                                                     └─►  resources               (Flow 4: pon:inUse, only when Fulfilled;
                                                                                                    read back next time as the held choice)
```

Which graph to look at:

| To see… | Look in |
|---|---|
| Whether each condition is met (`icm:result`), and why not | `reports/{id}`, via the `results` helper in Step 5 |
| What the engine itself decided, in raw form (and to check java, jena and compare agree) | `intents/{id}/inferred` |
| The handler's record of the latest state and per-condition results | `intents/{id}/handlerState` |
| Which ports and tags are in use, and which intent holds them (`pon:assignedToService`) | `resources` |

To list every graph and its size in Fuseki:

```sparql
SELECT ?g (COUNT(*) AS ?triples) WHERE { GRAPH ?g { ?s ?p ?o } } GROUP BY ?g ORDER BY ?g
```

---

## Architecture

```
POST /intent          →  TDB2 intent named graph
POST /observation     →  TDB2 observations graph  (per intent)
                                 ↓ merged at evaluation time
         EvaluationEngine (intent.evaluator: java | jena | compare), access profile
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

Steps 2, 5 and 6 query the graph store through Fuseki (SPARQL + web UI on port 3030), so start the server with
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
{"status": "UP", "graph": "UP", "evaluator": {"engine": "java", "ready": true}}
```

> All steps below use port **8001**. Set once: `BASE=http://localhost:8001/tmf-api/intentManagement/v5`

### Stop the server

**Docker:**

```bash
docker compose --profile access down      # stop and remove the container; the data is kept
docker compose --profile access stop      # or only stop it; `start` resumes the same container
```

`--profile access` is required: the services are profile-gated, so without it Compose finds nothing to stop.

**Dev server:** press `Ctrl-C` in the terminal running `mvn spring-boot:run`.

### Optionally clear the data

There is no separate volume for observations. Observations are stored in the same TDB2 store as everything else
(intents, observations, reports, the handler state and inferred results, and the resource inventory with its
allocations), and that store lives in one named Docker volume, `tdb2-access`. Compose prefixes the volume name with
the project name, so here it is `bbf_921_java_tdb2-access`; `docker volume ls` shows it. Clearing the observations
therefore means clearing the store:

```bash
docker compose --profile access down -v            # stop, remove the container AND this profile's data volume
# or, once the container is stopped or removed:
docker volume rm bbf_921_java_tdb2-access
```

This removes every intent, observation and report, and the allocations the handler wrote (`pon:inUse`,
`pon:assignedToService`). The next start reloads the PON inventory from `BBF_access/`, so every UNI port and C-tag is
free again, as in Step 2.

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
APP_PROFILES=access,fuseki docker compose --profile access up --build
INTENT_EVALUATOR=java APP_PROFILES=access,fuseki docker compose --profile access up --build    # same, explicit

# Dev server
mvn spring-boot:run -Dspring-boot.run.profiles=access,fuseki
mvn spring-boot:run -Dspring-boot.run.profiles=access,fuseki -Dspring-boot.run.arguments=--intent.evaluator=java    # same, explicit
```

**Jena engine only, or compare mode:**

```bash
# Docker
INTENT_EVALUATOR=jena    APP_PROFILES=access,fuseki docker compose --profile access up --build
INTENT_EVALUATOR=compare APP_PROFILES=access,fuseki docker compose --profile access up --build

# Dev server
mvn spring-boot:run -Dspring-boot.run.profiles=access,fuseki -Dspring-boot.run.arguments=--intent.evaluator=jena
INTENT_EVALUATOR=compare mvn spring-boot:run -Dspring-boot.run.profiles=access,fuseki
```

**Check which engine is running** (field `evaluator`):

```bash
curl http://localhost:8001/health
# {"status":"UP","graph":"UP","evaluator":{"engine":"java","ready":true}}
```

The Jena engine needs the licensed ontology files in `ontology/`; without them `jena` reports `"ready": false` (HTTP
503) and evaluations are skipped, and `compare` skips the Jena side (logging one `compare: secondary engine 'jena'
skipped, not ready` info line per evaluation) and returns Java's answer. In `compare` mode, a difference between
the engines is logged as a `WARN` line starting `compare:`; none means the engines agree on everything you ran:

```bash
docker compose --profile access logs | grep -E 'WARN.*compare:'
```

After an evaluation, the engine's result graph is in the named graph
`http://tmforum.org/api/v5/intents/{id}/inferred`, which you can browse in Fuseki.

---

## The intent expression

The HSI expression (`seed_data/hsionlyintent_v0.5.ttl`) uses TIO set constructors. Pools are combined with an
explicit `set:intersection` (several set functions on one node would combine as a union, TR292A):

```
┌─ bbf:HSIIntent  log:allOf ──────────────────────────────────────────┐
│    UNI expectation, S-tag expectation, C-tag expectation,            │
│    performance expectation, monitoring expectation                   │
│                                                                      │
│  icm:DeliveryExpectation (UNI)                                       │
│    icm:target          bbf:SelectedUNIInterface                      │
│    icm:deliveryType    pon:UNIPort        <- needed, see below       │
│    icm:chooseFrom      bbf:AvailableUNIInterfaces                    │
│                                                                      │
│  bbf:AvailableUNIInterfaces                                          │
│    set:intersection (                                                │
│      [ set:resourcesOfType ( pon:UNIPort ) ]                         │
│      [ set:resourcesWithPropertyObject ( pon:inUse false ) ]         │
│      [ set:resourcesWithPropertyObject ( pon:operationalState        │
│                                          pon:Up ) ]                  │
│      [ set:resourcesWithPropertyObject ( pon:provisioningState       │
│                                          pon:Ready ) ] )             │
│                                                                      │
│  (S-VLAN and C-tag expectations choose the same way; the C-tag pool  │
│   is limited to the chosen S-VLAN)                                   │
│                                                                      │
│  icm:PropertyExpectation  log:allOf  (5x metric conditions)          │
│    quan:atLeast ( DownstreamBandwidthMetric 100 Mbps )               │
│    quan:atLeast ( UpstreamBandwidthMetric 20 Mbps )                  │
│    quan:smaller ( LatencyMetric 25 ms )                              │
│    quan:smaller ( JitterMetric 3 ms )                                │
│    quan:smaller ( PacketLossMetric 0.1 percent )                     │
└──────────────────────────────────────────────────────────────────────┘
```

`bbf:AvailableUNIInterfaces` resolves to all UNI ports that are:
- typed `pon:UNIPort`
- have `pon:inUse false`
- have `pon:operationalState pon:Up` and `pon:provisioningState pon:Ready`

The handler chooses one member of that pool (the lowest IRI) as the member of `bbf:SelectedUNIInterface`.

> **`icm:deliveryType` is required** on every delivery expectation whose resource the intent should keep.
> A held resource is only kept on re-evaluation when it is an instance of the expectation's delivery type; an
> expectation without one leaks a newly allocated resource on each re-evaluation (open bd issue "Untyped delivery
> expectation leaks one resource per re-evaluation").

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

The API returns `201 Created` with `lifecycleStatus: ACKNOWLEDGED`, and the first evaluation runs in the background.

### See the intent in Fuseki

The intent is stored as RDF, and with the `fuseki` profile you can look at it directly (see "The graphs this solution
uses" above for what each graph is). Either open the Fuseki UI at <http://localhost:3030>, choose the dataset
`/intents`, and paste a query below into the **query** tab (replace `$INTENT_ID` with your intent's id,
`echo "$INTENT_ID"`), or run it from the shell with this helper, which the later steps use too (define it again if you
are in a new shell):

```bash
sparql() { curl -s -G http://localhost:3030/intents/sparql -H 'Accept: text/csv' --data-urlencode "query=$1"; }
```

**The intent resource** (id, name, lifecycle status, the IRI of its expression; the expression text itself is a long
string, so it is left out here):

```bash
sparql "SELECT ?p ?o WHERE { GRAPH <http://tmforum.org/api/v5/intents/$INTENT_ID> { ?s ?p ?o }
        FILTER (STRLEN(STR(?o)) < 200) } ORDER BY ?p"
```

Expected: eleven rows, among them `lifecycleStatus` = `ACKNOWLEDGED`, your `name`, the `id` and `href`, the
`expressionIri` (`bbf:HSIOnlyIntent`) and `hasExpression` (the intent's own `…/expression` node), and the two types
`Intent` and `TurtleExpression`. After Step 3 (activate) `lifecycleStatus` is `ACTIVE` and a `statusChangeDate` row
appears.

**The expression, exactly as stored.** The API and the store keep the expression as an opaque string, and only the
evaluation engine reads it:

```bash
sparql "PREFIX tmf: <http://tmforum.org/api/v5/>
SELECT ?value WHERE { GRAPH <http://tmforum.org/api/v5/intents/$INTENT_ID> { ?e tmf:expressionValue ?value } }"
```

Expected: one row, the Turtle you posted (the contents of `seed_data/hsionlyintent_v0.5.ttl`). The `curl` output is CSV,
which doubles every double quote inside the text (`""North region""`); the Fuseki UI shows it as it was posted.

**Which graphs does this intent have?**

```bash
sparql "SELECT ?g (COUNT(*) AS ?triples) WHERE { GRAPH ?g { ?s ?p ?o }
        FILTER (CONTAINS(STR(?g), \"$INTENT_ID\")) } GROUP BY ?g ORDER BY ?g"
```

Expected: three graphs already: the intent itself (12 triples, 13 once it is activated), `…/handlerState` (42) and
`…/inferred` (29). The last two exist because the first evaluation ran as soon as the intent was created; Step 4
shows its report. A fourth graph, `…/observations`, appears in Step 5 when the first observation is posted.

---

## Step 2 — Verify free UNIs exist in the inventory

Before activating the intent, confirm the resource graph contains at least one UNI port the evaluator
can select; the intent can only reach `Fulfilled`, and allocate resources, if it does.
The UNI pool in the intent expression (`bbf:AvailableUNIInterfaces`) picks UNI ports that are not in use
(`pon:inUse false`), operationally `Up` and provisioning `Ready`.

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
S-VLAN that was chosen, never from another one. Each delivery expectation in the intent names the class of resource
it must deliver (`icm:deliveryType`): `pon:UNIPort`, `pon:SVLANAllocation` and `pon:CTAGAllocation`.
`pon:HSIService` is the delivery description. The same Fuseki helper (`sparql`, defined in Step 1) shows both.

**S-VLANs.** A clean store has three:

```bash
sparql 'PREFIX pon: <http://broadband-forum.org/ont/pon-resource#>
SELECT ?svlan ?sTag ?serviceType WHERE {
  GRAPH <http://tmforum.org/api/v5/resources> {
    ?svlan a pon:SVLANAllocation ; pon:sTag ?sTag ; pon:serviceType ?serviceType .
  }
} ORDER BY ?svlan'
```

Expected: `SVLAN-N-HSI` and `SVLAN-S-HSI` (both HSI, S-tag 100) and `SVLAN-N-VoIP` (S-tag 200, VoIP, so never used for
HSI).

**Free C-tags.** There are six, all on the two HSI S-VLANs:

```bash
sparql 'PREFIX pon: <http://broadband-forum.org/ont/pon-resource#>
SELECT ?ctag ?cTag ?svlan WHERE {
  GRAPH <http://tmforum.org/api/v5/resources> {
    ?ctag a pon:CTAGAllocation ; pon:inUse false ; pon:cTag ?cTag ; pon:fromSVLAN ?svlan .
  }
} ORDER BY ?svlan ?ctag'
```

Expected: six rows. On `SVLAN-N-HSI`: `CTAG-N-001` (200), `CTAG-N-003` (202), `CTAG-N-004` (203). On `SVLAN-S-HSI`:
`CTAG-S-001` (200), `CTAG-S-002` (201), `CTAG-S-003` (202). The C-tag value 200 appears on both S-VLANs: it is the
inner tag, and only the pair (S-VLAN, C-tag) is unique, which is why the C-tag must come from the chosen S-VLAN. The
seventh C-tag, `CTAG-N-002` (201), is already in use in the seed data, so it is not listed.

---

## Step 3 — Activate

```bash
curl -s -X PATCH "$BASE/intent/$INTENT_ID" \
  -H "Content-Type: application/json" \
  -d '{"lifecycleStatus": "ACTIVE"}' | python3 -m json.tool
```

Expected: `"lifecycleStatus": "ACTIVE"`. Activation triggers another evaluation. The stored intent changed too:

```bash
sparql "PREFIX tmf: <http://tmforum.org/api/v5/>
SELECT ?status ?changed WHERE {
  GRAPH <http://tmforum.org/api/v5/intents/$INTENT_ID> { ?s tmf:lifecycleStatus ?status ; tmf:statusChangeDate ?changed }
}"
```

Expected: one row, `ACTIVE` and the time of the change (the intent graph gained a `statusChangeDate` triple, 13 in all).

---

## Step 4 — Check the initial intent report

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
metrics with no observation.

You will see **two** reports, and they look the same. They are two evaluations of the same intent: one ran when you
created it (Step 1) and one when you activated it (Step 3). Nothing changed between them (same expression, still no
observations), so the state and the reason are identical; only the `creationDate` differs, by however long you took
between the two steps. A new report is written every time the intent is evaluated, even when the result is unchanged,
so the count goes up with each step below (Step 5 shows it as `reports so far`).

Each report also carries its detail in `expression`. That is a `TurtleExpression` whose `expressionValue` is the
TM Forum TR290 intent report: a report for the intent, for each expectation and for each condition, with the
truth value of each (`icm:result`), what it was derived from (`icm:resultFrom`), why it is false
(`icm:reason`) and the resources chosen (`icm:targetReport`). To read the newest one:

```bash
curl -s "$BASE/intent/$INTENT_ID/intentReport" \
  | python3 -c "import sys, json; print(json.load(sys.stdin)[0]['expression']['expressionValue'])"
```

It is long (one entry per intent element), so the steps below query it in Fuseki instead (the `results` helper in
Step 5), which shows one row per element.

**Pretty-print the full report.** The raw Turtle above is hard to read: long URIs and no obvious order. This
prints the whole newest report as a tree, from the report's own fields down to the intent, its expectations and
their conditions, with the `icm:result` of every element, the reason for each false one and the resources chosen:

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

Expected for the initial report: the intent is `false`, the three delivery expectations are `true` with the
resources chosen, the monitoring expectation is `true`, and the performance expectation is `false` because all
five of its conditions are `false` with a `no observation` reason:

```
IntentReport 0b1c…
  creationDate:         2026-10-19T08:19:04.177794517Z
  intentId:             8c39…
  intentHandlingState:  Degraded
  intentHandlingReason: Conditions not met: atLeast: no observation: DownstreamBandwidthMetric; … PacketLossMetric

IntentReport                 HSIIntent                              result=false
    DeliveryExpectationReport    HSICTAGProvisioningExpectation         result=true
      · target SelectedCTAGAllocation: CTAG-N-001
    ReportingExpectationReport   HSIMonitoringExpectation               result=true
    PropertyExpectationReport    HSIPerformanceExpectation              result=false
      · target HSIServiceTarget: HSIServiceInstance
        ConditionReport              HSI_DownstreamBandwidthCondition       result=false
          · reason: no observation: DownstreamBandwidthMetric
        ConditionReport              HSI_JitterCondition                    result=false
          · reason: no observation: JitterMetric
        ConditionReport              HSI_LatencyCondition                   result=false
          · reason: no observation: LatencyMetric
        ConditionReport              HSI_PacketLossCondition                result=false
          · reason: no observation: PacketLossMetric
        ConditionReport              HSI_UpstreamBandwidthCondition         result=false
          · reason: no observation: UpstreamBandwidthMetric
    DeliveryExpectationReport    HSISTagProvisioningExpectation         result=true
      · target SelectedSVLANAllocation: SVLAN-N-HSI
    DeliveryExpectationReport    HSIServiceProvisioningExpectation      result=true
      · target SelectedUNIInterface: UNI-001-1
```

Run the same snippet after any later step to see the report change; it always shows the newest one. (To see an
older report, change `[0]` to a later index: the list is newest first.)

### See the intent and its reports in Fuseki

The intent and every report are stored as RDF, and with the `fuseki` profile you can look at them directly (see "The
graphs this solution uses" above for what each graph is). Paste a query below into the Fuseki UI query tab (replace
`$INTENT_ID` with your intent's id), or run it from the shell with the `sparql` helper from Step 1 (define it again if
you are in a new shell). The intent resource, its stored expression and its graphs are in Step 1; these queries look
at its reports.

**Its reports, newest first.** Each report is its own named graph, linked to the intent by `tmf:parentIntent`. Right
after Steps 1 and 3 there are two (one when the intent was created, one when it was activated), both `Degraded`:

```bash
sparql "PREFIX tmf: <http://tmforum.org/api/v5/>
PREFIX dcterms: <http://purl.org/dc/terms/>
SELECT ?report ?created ?state WHERE {
  GRAPH ?report { ?report tmf:parentIntent <http://tmforum.org/api/v5/intents/$INTENT_ID> ;
                          dcterms:created ?created .
                  OPTIONAL { ?report tmf:intentHandlingState ?state } }
} ORDER BY DESC(?created)"
```

**Everything in the newest report graph.** The first part is the `IntentReport` resource (state, reason, link
to its intent). The rest is the TR290 report itself, one node per intent element with `icm:result`, `icm:about`,
`icm:resultFrom` and `icm:reason`; it is also kept whole, as text, in `expressionValue`:

```bash
REPORT=$(sparql "PREFIX tmf: <http://tmforum.org/api/v5/>
PREFIX dcterms: <http://purl.org/dc/terms/>
SELECT ?report WHERE { GRAPH ?report { ?report tmf:parentIntent <http://tmforum.org/api/v5/intents/$INTENT_ID> ;
                                               dcterms:created ?created } } ORDER BY DESC(?created) LIMIT 1" \
  | tail -1 | tr -d '\r')

sparql "SELECT ?s ?p ?o WHERE { GRAPH <$REPORT> { ?s ?p ?o } } ORDER BY ?s ?p ?o"
```

One row per element, with its result and reason, is easier to read: that is the `results` helper in Step 5.
The engine's own answer, before it was turned into a report, is in the `…/inferred` graph:

```bash
sparql "SELECT ?s ?p ?o WHERE { GRAPH <http://tmforum.org/api/v5/intents/$INTENT_ID/inferred> { ?s ?p ?o } }
        ORDER BY ?s ?p"
```

Expected on the initial state: `rdf:value false` for `HSIIntent`, `HSIPerformanceExpectation` and the five
conditions, `rdf:value true` for the three delivery expectations and the monitoring expectation, the chosen
resources as `rdfs:member` of the selected targets, and an `icm:reason` ("no observation: …") on each condition.
There is no `icm:result` here: that is in the report graph.

---

## Step 5 — Show the intent is not Fulfilled until every condition is met

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

Observations are quantities (TR292D): a number and a unit. The conditions state their bounds with units
(`100 Mbps`, `25 ms`, `0.1 percent`), so each observation is posted with its unit. Unit prefixes are converted
(`0.15 Gbps` satisfies `>= 100 Mbps`); a value in a different dimension, or without a unit, cannot be compared
and the condition is `false` with a "unit mismatch" reason.

Conditions 1–3 depend only on the inventory, so they pass as soon as the intent is active. Conditions 4–8 each
need a metric observation. You will post the metrics in two halves and check the intent report and the
per-condition results after each.

Set up the variables and helpers once. `results` reads the TR290 intent report the handler stores with every
IntentReport: an `icm:result` for the intent, each expectation and each condition. It queries Fuseki, so it needs
the Fuseki profile (`access,fuseki`):

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
  | sed 's|http://tio.models.tmforum.org/tio/v3.6.0/IntentCommonModel/||; s|http://broadband-forum.org/Intent#||g; s|http://broadband-forum.org/ont/pon-resource#||g'
}
```

This block only defines the variables and the three helpers, so it prints nothing. It needs `BASE` and
`INTENT_ID` from Step 1; if you are in a new shell, check with `echo "$BASE" "$INTENT_ID"` and set them again
if they are empty.

> **How the intent is reported on (TR290 §4).** Each evaluation creates a TR290 report with one report per intent
> element: an `icm:IntentReport`, an expectation report per expectation (`icm:DeliveryExpectationReport`,
> `icm:PropertyExpectationReport`, ...) and an `icm:ConditionReport` per condition. Each says which element it
> covers (`icm:about`), its truth value (`icm:result`; for the intent and expectations `true` means compliant), and
> which reports it was derived from (`icm:resultFrom`), so the intent's result is built up from its conditions.
> A report that is `false` carries an `icm:reason`. A report for an expectation also names its target's resources
> (`icm:targetReport`), which for a delivery expectation are the resources chosen. The reporting expectation in the intent decides which elements are reported (TR290 §4.2).
> Observed values are left out because it is an `icm:ReportingExpectation`; an `icm:ObservationReportingExpectation`
> would add them (`met:observed`).

### 5a — First half: bandwidth metrics only

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

Expected: 11 rows, one per reported element, built up from the bottom:

| `kind` | `element` | `result` | Notes |
|---|---|---|---|
| `ConditionReport` | `HSI_DownstreamBandwidthCondition`, `HSI_UpstreamBandwidthCondition` | `true` | |
| `ConditionReport` | `HSI_LatencyCondition`, `HSI_JitterCondition`, `HSI_PacketLossCondition` | `false` | `reason` = `no observation: …` |
| `DeliveryExpectationReport` | the UNI, S-tag and C-tag expectations | `true` | `target` = the resource chosen: `UNI-001-1`, `SVLAN-N-HSI`, `CTAG-N-001` (candidates are tried in name order, so the choice is repeatable) |
| `PropertyExpectationReport` | `HSIPerformanceExpectation` | `false` | `target` = `HSIServiceInstance`, the resource its conditions apply to; five conditions, three of them false |
| `ReportingExpectationReport` | `HSIMonitoringExpectation` | `true` | nothing to fail |
| `IntentReport` | `HSIIntent` | `false` | not compliant |

Order matters in the intent: the S-tag is selected before the C-tag, because the C-tag pool is built from the
chosen S-VLAN.

Nothing is allocated yet. The UNI, S-tag and C-tag are only *selected*; the handler writes the allocation back
when the intent becomes Fulfilled (see Step 6).

### 5b — Second half: latency, jitter and packet loss

```bash
post_metric LatencyMetric      8.0  ms         # < 25 ms: pass
post_metric JitterMetric       1.2  ms         # < 3 ms:  pass
sleep 2
report
results
```

Expected: still **`Degraded`**. Only `PacketLossMetric` is left in the reason. In `results`, four of the five
`ConditionReport` rows are now `true` (only `HSI_PacketLossCondition` is `false`, with its reason), and the
performance expectation and the intent are still `false`.

```bash
post_metric PacketLossMetric   0.02 percent    # < 0.1 percent: pass
echo "Waiting for evaluation..."
sleep 2
report
results
```

Expected: **`Fulfilled`** with reason `None`, and all 11 rows `result = true`, up to and including the
`IntentReport`. The intent became compliant only on the last observation, when the final condition passed.

---

## Step 6 — Verify Fulfilled + resource allocation

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
*selected* (visible in the per-condition results from Step 5) but not claimed by one service the way a port and
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
| `UNI-002-1` | `existing-service-placeholder` | Already in use in the seed data (see Step 2); not part of your intent |
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

## Step 7 — Verify idempotency

Post the same observation a second time. This triggers another evaluation of an intent that already holds its
resources:

```bash
post_metric DownstreamBandwidthMetric 150.0 Mbps

sleep 1

report
results
```

Expected: still **`Fulfilled`**, all rows `true`, and the three `DeliveryExpectationReport` rows still show the
**same** `target` resources as in Step 5 (`UNI-001-1`, `SVLAN-N-HSI`, `CTAG-N-001`), even though the UNI and
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

## Step 8 — Exhaust the inventory

To trigger `Degraded` via exhausted resources, patch a test expression that requires
a type with no free instances. (The PON inventory in `pon_resource_data.ttl` may be
fully allocated after Step 6.)

### 8a — Create a competing intent

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

### 8b — Post the same metric observations

```bash
COMP_OBS="$BASE/intent/$COMP_ID/observation"

curl -s -X POST "$COMP_OBS" -H "Content-Type: application/json" \
  -d "{\"metricUri\": \"${BBF}DownstreamBandwidthMetric\", \"value\": 150.0, \"unit\": \"Mbps\"}"
# ... (repeat for all 5 metrics, with their units as in Step 5)

sleep 2

curl -s "$BASE/intent/$COMP_ID/intentReport" \
  | python3 -c "import sys,json; r=json.load(sys.stdin); print('State:', r[0]['intentHandlingState'])"
```

If no free `pon:inUse false` UNI ports remain, the set constructor resolves to an
empty set → the `DeliveryExpectation` fails → `Degraded`.

---

## Step 9 — Multiple concurrent intents (allOf composition)

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

## Step 10 — Flow 1: ProbeIntent (could this be delivered?)

Step 6 showed Flow 4, which allocates resources for a `Fulfilled` intent. A **ProbeIntent** is the opposite: a
capability probe that asks "could this be delivered?" without taking anything. Its first evaluation decides its fate
(Flow 1): `Fulfilled` moves it to `ACTIVE`, `Degraded` moves it to `TERMINATED`. Flow 4 skips probes, so a probe
never allocates a resource.

Set up the helpers, and take a snapshot of the resources in use so you can show the probes change nothing:

```bash
in_use() {        # every resource in use, and which service holds it (Fuseki)
  curl -s -G http://localhost:3030/intents/sparql -H 'Accept: text/csv' \
    --data-urlencode 'query=PREFIX pon: <http://broadband-forum.org/ont/pon-resource#>
SELECT ?resource ?svc WHERE { GRAPH <http://tmforum.org/api/v5/resources> {
  ?resource pon:inUse true ; pon:assignedToService ?svc } } ORDER BY ?resource' \
    | sed 's|http://broadband-forum.org/ont/pon-resource#||g'
}

lifecycle() {     # usage: lifecycle <intent id>
  curl -s "$BASE/intent/$1" | python3 -c "import sys,json; print('lifecycleStatus:', json.load(sys.stdin)['lifecycleStatus'])"
}

create_probe() {  # usage: create_probe "<name>" "<turtle>"  -> prints the new intent's id
  curl -s -X POST "$BASE/intent" -H "Content-Type: application/json" -d "{
    \"@type\": \"ProbeIntent\",
    \"name\": \"$1\",
    \"expression\": {
      \"@type\": \"TurtleExpression\",
      \"expressionValue\": $(python3 -c "import sys, json; print(json.dumps(sys.argv[1]))" "$2")
    }
  }" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])"
}

probe_ttl() {     # usage: probe_ttl '<extra pool filter, or empty>'
  cat <<EOF
@prefix pon:  <http://broadband-forum.org/ont/pon-resource#> .
@prefix bbf:  <http://broadband-forum.org/Intent#> .
@prefix icm:  <http://tio.models.tmforum.org/tio/v3.6.0/IntentCommonModel/> .
@prefix log:  <http://tio.models.tmforum.org/tio/v3.6.0/LogicalOperators/> .
@prefix set:  <http://tio.models.tmforum.org/tio/v3.6.0/SetOperators/> .

bbf:ProbeIntentRoot  a icm:Intent ;
    log:allOf ( bbf:ProbeUNIExpectation ) .

# Can a UNI port be delivered? A probe only checks availability: nothing is allocated.
bbf:ProbeUNIExpectation  a icm:DeliveryExpectation ;
    icm:target       bbf:ProbeSelectedUNI ;
    icm:deliveryType pon:UNIPort ;
    icm:chooseFrom   bbf:ProbeUNIPool .

bbf:ProbeSelectedUNI  a icm:Target .

bbf:ProbeUNIPool  a icm:Target ;
    set:intersection ( [ set:resourcesOfType ( pon:UNIPort ) ]
                       [ set:resourcesWithPropertyObject ( pon:inUse             false ) ]
                       [ set:resourcesWithPropertyObject ( pon:operationalState  pon:Up ) ]
                       [ set:resourcesWithPropertyObject ( pon:provisioningState pon:Ready ) ]
                       $1 ) .
EOF
}

IN_USE_BEFORE=$(in_use)
```

This block only defines helpers and takes the snapshot, so it prints nothing.

### 10a — A probe that passes

A free, `Up`, `Ready` UNI port exists, so the probe is `Fulfilled`:

```bash
PROBE_PASS_ID=$(create_probe "UNI availability probe" "$(probe_ttl '')")
sleep 2
lifecycle "$PROBE_PASS_ID"
```

Expected: **`ACTIVE`**

### 10b — A probe that fails

The same probe, but asking for a port on an ONT the inventory does not have (`pon:ONT-999`). No port qualifies, so
the probe is `Degraded`:

```bash
PROBE_FAIL_ID=$(create_probe "UNI availability probe, unknown ONT" \
  "$(probe_ttl '[ set:resourcesWithPropertyObject ( pon:belongsToONT pon:ONT-999 ) ]')")
sleep 2
lifecycle "$PROBE_FAIL_ID"
curl -s "$BASE/intent/$PROBE_FAIL_ID/intentReport" \
  | python3 -c "import sys,json; r=json.load(sys.stdin); print(r[0]['intentHandlingState'], '|', r[0]['intentHandlingReason'])"
```

Expected: **`TERMINATED`**, and the report says why:
`Degraded | Conditions not met: DeliveryExpectation: no candidate of type UNIPort in chooseFrom pool`.

### 10c — The probes took nothing

```bash
[ "$(in_use)" = "$IN_USE_BEFORE" ] && echo "unchanged: the probes allocated nothing" || echo "CHANGED"
```

Expected: **`unchanged: the probes allocated nothing`**. Compare with Step 6: a `Fulfilled` intent that is not a probe
does allocate.

---

## Step 11 — Flow 2: a `DEGRADED` intent recovers

Flow 2 brings an intent back: when an evaluation is `Fulfilled` and the intent's `lifecycleStatus` is `DEGRADED`, the
handler moves it to `ACTIVE`. This reuses the intent from Step 1, which is `ACTIVE` and holds its UNI port and
C-tag.

> **`DEGRADED` is set by hand here.** The handler does not yet move an intent to `DEGRADED` by itself when an
> evaluation comes out `Degraded` (tracked as `bbf921j-9pf`), so this step marks it with a PATCH, as an operator or
> another system would.

```bash
curl -s -X PATCH "$BASE/intent/$INTENT_ID" -H "Content-Type: application/json" \
  -d '{"lifecycleStatus": "DEGRADED"}' > /dev/null

sleep 1
lifecycle "$INTENT_ID"
report
```

Expected: `lifecycleStatus: DEGRADED`. The PATCH does not evaluate anything, so the newest report is the one from before,
still `Fulfilled`.

A `DEGRADED` intent is evaluated again when an observation arrives (or its expression is patched). Post one, a
latency still inside its bound:

```bash
post_metric LatencyMetric 9.0 ms
sleep 3
lifecycle "$INTENT_ID"
report
```

Expected: **`lifecycleStatus: ACTIVE`** — the evaluation is `Fulfilled` (one more report than before), so Flow 2 moved
the intent back to `ACTIVE`. Its resources did not change: it still holds the same UNI port and C-tag, because Flow 4
claims only what is missing.

```bash
in_use | grep -c "$INTENT_ID"
```

Expected: `2`

---

## Step 12 — Flow 3: Best/Propose

Flow 3 handles an intent whose bounds are too tight for what the network can do. When an `ACKNOWLEDGED` or `ACTIVE`
`Intent` evaluates as `Degraded`, the handler rewrites each **failed** quantity bound in the stored expression to the
value actually observed, and fires `IntentAttributeValueChangeEvent`. It changes only the condition that failed: a
condition that passed keeps its bound, even if another condition of the same kind failed. The new bounds are used by
the *next* evaluation; the rewrite does not re-evaluate by itself.

An intent that is not `ACTIVE` is not evaluated when an observation arrives. So post the observations first, then
activate the intent: activation is what triggers the evaluation.

### 12a — Create an intent with a downstream bound that is too tight

The Step 1 expression with the downstream bound raised from 100 to 500 Mbps (this network can deliver 150):

```bash
TIGHT=$(python3 -c "import sys, json; print(json.dumps(open('seed_data/hsionlyintent_v0.5.ttl').read().replace('\"100\"^^xsd:decimal', '\"500\"^^xsd:decimal')))")

TIGHT_INTENT=$(curl -s -X POST "$BASE/intent" -H "Content-Type: application/json" -d "{
  \"@type\": \"Intent\",
  \"name\": \"Access Flow 3 demo, tight bound\",
  \"expression\": {\"@type\": \"TurtleExpression\", \"iri\": \"http://broadband-forum.org/Intent#HSIOnlyIntent\", \"expressionValue\": $TIGHT}
}")
TIGHT_ID=$(echo "$TIGHT_INTENT" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")
echo "Tight intent: $TIGHT_ID"

bounds() {        # the downstream and upstream bounds in the stored expression
  curl -s "$BASE/intent/$TIGHT_ID" | python3 -c "
import sys, json, re
t = json.load(sys.stdin)['expression']['expressionValue']
for name in ('Downstream', 'Upstream'):
    m = re.search(r'HSI_%sBandwidthCondition\s+(?:a|rdf:type)\s+icm:Condition.*?rdf:value\s+(\"?[0-9.]+)' % name, t, re.S)
    print(f'{name} bound:', m.group(1).strip(chr(34)), 'Mbps')
"
}
bounds
```

Expected: `Downstream bound: 500 Mbps` and `Upstream bound: 20 Mbps`.

### 12b — Observe, then activate

Post the same good observations as Step 5, to the new intent, while it is still `ACKNOWLEDGED`, then activate it:

```bash
tight_metric() {  # usage: tight_metric DownstreamBandwidthMetric 150.0 Mbps
  curl -s -o /dev/null -X POST "$BASE/intent/$TIGHT_ID/observation" -H "Content-Type: application/json" \
    -d "{\"metricUri\": \"${BBF}$1\", \"value\": $2, \"unit\": \"$3\"}"
}
tight_metric DownstreamBandwidthMetric 150.0 Mbps
tight_metric UpstreamBandwidthMetric   30.0  Mbps
tight_metric LatencyMetric             8.0   ms
tight_metric JitterMetric              1.2   ms
tight_metric PacketLossMetric          0.02  percent

curl -s -X PATCH "$BASE/intent/$TIGHT_ID" -H "Content-Type: application/json" \
  -d '{"lifecycleStatus": "ACTIVE"}' > /dev/null
sleep 3

curl -s "$BASE/intent/$TIGHT_ID/intentReport" \
  | python3 -c "import sys,json; r=json.load(sys.stdin); print(len(r), 'reports | latest:', r[0]['intentHandlingState'], '|', r[0]['intentHandlingReason'])"
```

Expected: **`Degraded`**, because only the downstream condition failed: `150.0 Mbps >= 500 Mbps: FAIL`. Nothing was
allocated: Flow 4 runs only for a `Fulfilled` intent.

### 12c — Inspect the proposal

```bash
bounds
```

Expected: **`Downstream bound: 150.0 Mbps`** (was 500) and **`Upstream bound: 20 Mbps`** (unchanged: that condition
passed). The stored expression now asks for what the network can deliver. The rewritten Turtle is re-serialised, so
it looks different from what you posted, but it means the same.

### 12d — The new bound takes effect, and Flow 4 follows

```bash
tight_metric DownstreamBandwidthMetric 150.0 Mbps
sleep 4
curl -s "$BASE/intent/$TIGHT_ID/intentReport" \
  | python3 -c "import sys,json; r=json.load(sys.stdin); print(len(r), 'reports | latest:', r[0]['intentHandlingState'])"
in_use | grep -c "$TIGHT_ID"
```

Expected: **`Fulfilled`** — the next evaluation uses the 150 Mbps bound — and `2`: now that the intent is `Fulfilled`,
Flow 4 has allocated a UNI port and a C-tag to it. Flow 3 relaxed the bound, and Flow 4 then acted on the result.

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
| `TurtleEvaluator.computeSetConstructors()` | Java engine: resolves `set:resourcesOfType`, `set:resourcesWithPropertyObject` |
| `TurtleEvaluator.evalDeliveryExpectation()` | Java engine: checks `icm:target` container has members of `icm:deliveryType` |
| `JenaEvaluationEngine` + `rules/tio_a_sets.rules`, `tio_c_choose.rules`, `tio_b_delivery.rules` | Jena engine: the same set, choice and delivery logic as rules |
| `ReportBuilder`, `ResultAdapter` | Turn either engine's result graph into the TR290 report and the handler result |
| `HandlerStateWriter.writeResourceAllocation()` | Writes `pon:inUse true` + `pon:assignedToService` |
| `HandlerStateWriter.resourcesAlreadyAllocated()` | ASK-query idempotency guard |
| `EvaluationDispatcher.tryResourceAllocation()` | Flow 4: calls writeResourceAllocation on Fulfilled |
| `AccessDemoIT` | Integration test: verifies resource graph via `@Autowired Dataset` |

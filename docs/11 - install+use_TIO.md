# TIO Ontology — Install and Use

## Background

The `ontology/` directory contains the TM Forum Intent Ontology (TIO) v3.6.0 Turtle files.
The intent handler is supposed to load these into Fuseki and query against them to derive
`intentHandlingState` for each evaluated intent.

Four gaps prevented the ontology from being used at all.

---

## Gap 1 — Ontology never loaded at startup

`src/graph/schema_init.py` contains `initialise_schema()`, which calls `ensure_dataset()` and
then `load_ontology()`. `load_ontology()` reads every `.ttl` file in `ontology/` and merges it
into the named graph `http://tmforum.org/api/v5/ontology` via the Graph Store Protocol.

`src/main.py` lifespan only called `init_client()`. `initialise_schema()` was never invoked,
so the ontology TTL files sat on disk and never reached Fuseki.

**Fix:** call `initialise_schema(get_client())` from the FastAPI lifespan, wrapped in a
non-fatal try/except so that a cold start where Fuseki is not yet healthy logs a warning
rather than crashing.

---

## Gap 2 — Evaluator queried only the eval graph, never the ontology

`evaluator.py._STATE_QUERY` scoped its WHERE clause entirely to `GRAPH <{eval_graph}>`.
The ontology graph (`http://tmforum.org/api/v5/ontology`) was never referenced.

**Fix:** add a second `GRAPH <{ontology_graph}>` clause that validates the candidate state
value against the ontology — the state must be typed as `imo:IntentHandlingState` in the
ontology graph. This makes the ontology an active participant in evaluation, not dead weight.

---

## Gap 3 — Evaluator looked for wrong predicate

The old `_STATE_QUERY` queried for `imo:intentHandlingState`. That predicate does not exist
in the TIO ontology. The correct predicates are:

| Predicate | Defined in | Notes |
|---|---|---|
| `icm:intentHandlingState` | `IntentCommonModel.ttl` | Domain: `icm:IntentReport`; range: `imo:IntentHandlingState` |
| `imo:handlingState` | `IntentManagementOntology.ttl` | Alternative assignment property |

**Fix:** rewrite `_STATE_QUERY` to use `icm:intentHandlingState UNION imo:handlingState`,
with the ontology cross-reference from Gap 2.

---

## Gap 4 — Prefix typo and wrong separator

The evaluator defined:

```python
_IMO = "http://tio.models.tmforum.org/tio/v3.6.0/IntentManagntOntology#"
#                                                          ↑ typo        ↑ wrong
```

The ontology files use:

```turtle
@prefix imo: <http://tio.models.tmforum.org/tio/v3.6.0/IntentManagementOntology/> .
#                                                          ↑ correct              ↑ slash
```

Every SPARQL query using the old `_IMO` prefix would resolve to a different namespace than
anything actually defined in the ontology.

**Fix:** correct spelling and separator in both the evaluator and the evaluator unit tests.

---

## Corrected evaluation flow

```
evaluate_intent(intent_id, client)
  │
  ├─ 1. Query intent named graph for expressionType + expressionValue
  │        GRAPH <intents/{id}> { <uri> tmf:hasExpression ... }
  │
  ├─ 2. If TurtleExpression: load expressionValue into eval named graph
  │        GSP POST → <eval/{id}>
  │
  ├─ 3. Query for intentHandlingState using ontology validation
  │        GRAPH <eval/{id}>     { ?s icm:intentHandlingState ?state }
  │        UNION
  │        GRAPH <eval/{id}>     { ?s imo:handlingState ?state }
  │        GRAPH <ontology>      { ?state rdf:type imo:IntentHandlingState }
  │                                ↑ ontology validates the state value
  │
  └─ 4. DROP SILENT GRAPH <eval/{id}>    (always, in finally block)
```

---

## Ontology named graph contents

After startup, `http://tmforum.org/api/v5/ontology` contains all `.ttl` files merged:

| File | Defines |
|---|---|
| `IntentManagementOntology.ttl` | `imo:IntentHandlingState` class + state individuals |
| `IntentCommonModel.ttl` | `icm:intentHandlingState` property, `icm:IntentReport`, etc. |
| `IntentCommonModel.ttl` | `icm:handlingState` and `icm:updateState` properties |
| `IntentGuaranteeOntology.ttl` | `ig:GuaranteeStateDegraded` etc. |
| `IntentValidityOntology.ttl` | `iv:ValidityChange` event |
| `intent.ttl` | Consolidated model with all state individuals |
| Other `.ttl` files | Functions, sets, quantities, probing, proposals |

---

## Known state individuals (`imo:IntentHandlingState`)

| Individual | Meaning |
|---|---|
| `imo:StateDegraded` | System not compliant with intent requirements |
| `imo:StateCompliant` | System compliant (alias `imo:Complies`) |
| `imo:StateIntentReceived` | Intent received, not yet evaluated |
| `imo:StateFinalizing` | Handler wrapping up |
| `imo:StateNoUpdate` | No pending update |
| `imo:StateUpdateReceived` | Update received |

---

## Limitations

- Fuseki/TDB2 does not execute the Datalog rules in `ontology/tio-rules.dlog` or
  `ontology/rules/tmf_imo_eval.dlog`. A full reasoner (RDFox, Jena Rules) would be
  needed to infer `intentHandlingState` from expression content automatically.
- Without a reasoner, a Turtle expression must explicitly contain an
  `icm:intentHandlingState` or `imo:handlingState` triple for the evaluator to find a
  non-Degraded result. The ontology validates the state value but does not derive it.

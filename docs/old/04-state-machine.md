# Intent Lifecycle State Machine

## Valid States and Transitions
```
POST ──► ACKNOWLEDGED ──► ACTIVE ──► FULFILLED
                               │
                               ├──► DEGRADED
                               │
                               └──► SUSPENDED

(any state) ──► TERMINATED  [terminal — no exit]
```

## Rules
- New intents initialise to `ACKNOWLEDGED` on POST (201 response)
- Intent Owner sets: `ACKNOWLEDGED`, `TERMINATED`
- Intent Handler sets: `ACTIVE`, `FULFILLED`, `DEGRADED`, `SUSPENDED`, `TERMINATED`
- `TERMINATED` is terminal — reject any PATCH changing lifecycleStatus with 400
- Every state change must:
  1. Update `tmf:lifecycleStatus` literal on Intent node
  2. Set `tmf:statusChangeDate` to current UTC timestamp
  3. Update `dcterms:modified` on Intent node
  4. Write a write-once `StateChange` named graph (never update or delete)
  5. Fire async `IntentStatusChangeEvent` to all registered hubs

## RDFLib StateChange Storage
```python
from rdflib import ConjunctiveGraph, URIRef, Literal, XSD
from rdflib.namespace import RDF, DCTERMS
import uuid, datetime

def record_state_change(cg: ConjunctiveGraph, intent_uri: URIRef,
                         from_state: str, to_state: str):
    sc_id = str(uuid.uuid4())
    sc_graph = URIRef(f"{intent_uri}/stateChange/{sc_id}")
    g = cg.get_context(sc_graph)
    g.add((sc_graph, RDF.type, TMF.StateChange))
    g.add((sc_graph, TMF.fromState, Literal(from_state, datatype=XSD.string)))
    g.add((sc_graph, TMF.toState,   Literal(to_state,   datatype=XSD.string)))
    g.add((sc_graph, DCTERMS.created,
           Literal(datetime.datetime.utcnow().isoformat(), datatype=XSD.dateTime)))
    # StateChange named graphs are write-once — never call g.remove() on them
```

## State Trigger Events (TMF921A Domain Events)
| Event | Trigger |
|---|---|
| `IntentReceived` | Intent created by Owner |
| `IntentAccepted` | Handler accepts intent |
| `IntentRejected` | Handler rejects intent |
| `IntentRemoval` | Intent deleted |
| `IntentHandlingEnded` | Handler finishes processing |
| `StateComplies` | Fulfilled |
| `StateDegrades` | Degraded |
| `UpdateReceived` | PATCH received |
| `UpdateAccepted` | Handler accepts PATCH |
| `UpdateRejected` | Handler rejects PATCH |
| `UpdateFinished` | Handler completes PATCH |

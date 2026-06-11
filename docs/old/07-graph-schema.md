# Graph Schema — RDFox

## Architecture

RDFox replaces RDFLib as the graph store. It runs as a local server process
started by the FastAPI lifespan. Both the REST API layer and the Intent Handler
use the same RDFox instance via its REST/SPARQL endpoint.

```
FastAPI lifespan startup
  └── start_rdfox()        starts RDFox subprocess
  └── initialise_schema()
        ├── create_datastore()     creates "tmf921" datastore
        ├── load_ontology()        loads ontology/allttl.ttl → ontology named graph
        └── load_datalog_rules()   loads ontology/tio-rules.dlog → RDFox rules engine
```

## Named Graphs

```
http://tmforum.org/api/v5/ontology          — TIO v3.6.0 (read-only, loaded at startup)
http://tmforum.org/api/v5/intents/{id}      — one named graph per Intent/ProbeIntent
http://tmforum.org/api/v5/reports/{id}      — one named graph per IntentReport
http://tmforum.org/api/v5/hubs              — all Hub subscription triples
http://tmforum.org/api/v5/audit             — all StateChange audit entries (immutable)
http://tmforum.org/api/v5/eval/{id}         — temporary evaluation graph (Intent Handler only)
```

## Node Types and RDF Classes

```
tmf:Intent                — REST Intent resource
tmf:ProbeIntent           — subClassOf tmf:Intent (inferred by Datalog rules)
tmf:IntentReport          — REST IntentReport resource
tmf:Hub                   — notification subscription listener
tmf:StateChange           — immutable lifecycle audit entry
```

## Mandatory Predicates on Every Node

```
tmf:id            — UUID string (primary key, server-generated)
tmf:href          — full self-referencing URL
rdf:type          — class URI
tmf:createdAt     — ISO8601 UTC xsd:dateTime
tmf:updatedAt     — ISO8601 UTC xsd:dateTime
```

## SPARQL Pattern — List All Intents

RDFox Datalog rule propagates ProbeIntent → Intent so UNION is not needed:

```sparql
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX tmf: <http://tmforum.org/api/v5/>

SELECT ?intent ?id ?status WHERE {
    ?intent rdf:type tmf:Intent ;        # includes ProbeIntent via Datalog
            tmf:id ?id ;
            tmf:lifecycleStatus ?status .
}
ORDER BY ?id
LIMIT 100 OFFSET 0
```

## expressionValue Boundary Rule

```
REST API layer:
  - Stores expressionValue as xsd:string Literal — NEVER parsed into triples
  - Passes expressionValue to Intent Handler as-is

Intent Handler layer (src/handler/evaluator.py):
  - Loads expressionValue Turtle/JSON-LD into temporary eval named graph
  - RDFox Datalog rules fire against eval graph
  - Queries inferred compliance state
  - Drops eval graph after evaluation
```

## Reasoning

RDFox applies OWL RL + Datalog reasoning **natively and incrementally**.
No `owlrl` or `pyshacl` Python packages needed.
Rules are defined in `ontology/tio-rules.dlog`.

## Persistence

RDFox persists data to `data/rdfox/` using its native format.
No manual serialise/deserialise needed — RDFox handles durability.
Configure in `rdfox/server.properties`.

## GET /{id} Traversal Depth

```
Intent named graph
 ├── expressionValue     (opaque Literal — returned as-is)
 ├── IntentReport[]      (icm:about back-links — IDs + href only)
 ├── IntentSpecification (EntityRef only)
 ├── relatedParty[]      (JSON Literal on Intent node)
 ├── characteristic[]    (JSON Literal on Intent node)
 └── intentRelationship[](tmf:relatesTo — id, href, @type, relationshipType)
```

Do NOT traverse StateChange audit graph on standard GET.

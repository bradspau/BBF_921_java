# TMF921 Graph Schema — Jena/Fuseki Architecture

## Architecture

Apache Jena Fuseki/TDB2 is the authoritative RDF store for the TMF921 API and intent handler.
Python communicates with Fuseki over SPARQL 1.1 HTTP using async httpx.
Fuseki provides persistence through TDB2. No embedded RDF runtime is used in the application process.

## Named graphs

- `http://tmforum.org/api/v5/ontology` — read-only ontology graph loaded separately.
- `http://tmforum.org/api/v5/intents/{id}` — one named graph per Intent or ProbeIntent.
- `http://tmforum.org/api/v5/reports/{id}` — one named graph per IntentReport.
- `http://tmforum.org/api/v5/hubs` — all Hub subscription triples.
- `http://tmforum.org/api/v5/audit/{id}` — immutable StateChange audit graph.
- `http://tmforum.org/api/v5/eval/{id}` — temporary evaluation graph used only by the intent handler.

## Node types and mandatory predicates

Core RDF classes:
- `tmf:Intent`
- `tmf:ProbeIntent`
- `tmf:IntentReport`
- `tmf:IntentSpecification`
- `tmf:Hub`
- `tmf:StateChange`

Common predicates:
- `tmf:id`
- `tmf:href`
- `rdf:type`
- `dcterms:created`
- `dcterms:modified`
- `tmf:lifecycleStatus`
- `tmf:statusChangeDate`

## Query patterns

List all Intents:
```sparql
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX tmf: <http://tmforum.org/api/v5/>

SELECT ?intent ?id ?status
WHERE {
  GRAPH ?g {
    ?intent rdf:type tmf:Intent ;
            tmf:id ?id ;
            tmf:lifecycleStatus ?status .
  }
}
ORDER BY ?id
LIMIT 100 OFFSET 0
```

Count Intents:
```sparql
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX tmf: <http://tmforum.org/api/v5/>

SELECT (COUNT(?intent) AS ?count)
WHERE {
  GRAPH ?g {
    ?intent rdf:type tmf:Intent .
  }
}
```

## expressionValue boundary rule

The API layer stores `expressionValue` as an opaque string literal and returns it unchanged.
The API layer must never parse `expressionValue` into RDF triples.
The intent handler loads `expressionValue` into a temporary evaluation graph, runs store-backed reasoning or queries, reads the inferred result, and then drops the evaluation graph.

## Persistence and traversal rules

Fuseki/TDB2 is responsible for on-disk persistence.
Standard GET by id must not traverse audit graphs.
Intent GET returns the stored expression object as originally represented, plus related first-class substructures that belong to the resource view.

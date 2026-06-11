# TMF921 Resource Models

## Resources
| Resource | RDF Class | Description |
|---|---|---|
| `Intent` | `tmf:Intent` | Core. Formal description of expectations given to a technical system. |
| `ProbeIntent` | `tmf:ProbeIntent` | Extends Intent (`@type: ProbeIntent`, `@baseType: Intent`). Used for negotiation. |
| `IntentReport` | `tmf:IntentReport` | Sub-resource of Intent. Reports on fulfilment. One Intent → many IntentReports. |
| `IntentSpecification` | `tmf:IntentSpecification` | Specification/template for an Intent. |

## Intent Fields → RDF Predicates
| API Field | RDF Predicate | Notes |
|---|---|---|
| `id` | `tmf:id` | UUID, server-generated |
| `href` | `tmf:href` | Self-referencing URL, server-generated |
| `name` | `tmf:name` | Required |
| `@type` | `rdf:type` | Discriminator |
| `@baseType` | `tmf:baseType` | Immutable |
| `@schemaLocation` | `tmf:schemaLocation` | Immutable |
| `creationDate` | `dcterms:created` | ISO 8601 UTC, server-set |
| `lastUpdate` | `dcterms:modified` | ISO 8601 UTC, server-set |
| `lifecycleStatus` | `tmf:lifecycleStatus` | Enum string |
| `statusChangeDate` | `tmf:statusChangeDate` | Server-set on transition |
| `expression` | `tmf:hasExpression` | Link to Expression named graph |
| `description` | `tmf:description` | Optional |
| `priority` | `tmf:priority` | Optional |
| `context` | `tmf:context` | Optional |
| `isBundle` | `tmf:isBundle` | Boolean |
| `version` | `tmf:version` | Optional |
| `validFor` | `tmf:validFor` | TimePeriod object |

## Mandatory Attributes — POST /intent Input
```
name                (string, required)
@type               (string, required) — "Intent" or "ProbeIntent"
expression          (object, required)
expression.@type    (string, required) — "JsonLdExpression" or "TurtleExpression"
expression.iri      (string, required) — TIO model IRI
expression.expressionValue  (required)
```

## Mandatory Attributes — GET Response (must never be null)
**Intent:** `@type`, `href`, `name`, `expression.@type`, `expression.iri`, `expression.expressionValue`
**IntentReport:** `@type`, `href`, `name`, `creationDate`, `expression.@type`, `expression.iri`, `expression.expressionValue`
**IntentSpecification:** `@type`, `href`, `name`

## Conditional Mandatory (if parent present)
| Parent | Required child fields |
|---|---|
| `attachment` | `@type`, `attachmentType`, `id`, `mimeType`, `name` |
| `characteristic` | `@type`, `name`, `value` |
| `characteristic.characteristicRelationship` | `@type`, `id`, `relationshipType` |
| `intentRelationship` | `referredType`, `@type`, `id`, `relationshipType` |
| `intentRelationship.associationSpec` | `@type`, `id`, `name` |
| `relatedParty` | `@type`, `role` |
| `relatedParty.partyOrPartyRole` | `@type`, `id`, `name` |

## Polymorphism Fields (all resources)
| Field | Purpose |
|---|---|
| `@type` | Discriminator — actual class name — maps to `rdf:type` |
| `@baseType` | Explicit super-class — stored as `tmf:baseType` literal |
| `@schemaLocation` | URI to extending JSON Schema |
| `referredType` | On EntityRef: actual type of referred entity |

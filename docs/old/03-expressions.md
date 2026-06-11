# TMF921 Expression Types

## Abstract Rule
`expression` is abstract. Must be extended by a concrete type via `@type`.

## JsonLdExpression (MANDATORY support)
```json
{
  "@type": "JsonLdExpression",
  "@baseType": "Expression",
  "iri": "http://tio.models.tmforum.org/tio/v2.0.0/IntentCommonModel",
  "@schemaLocation": "https://mycsp.com:8080/tmf-api/schema/Common/JsonLdExpression.schema.json",
  "expressionValue": {
    "@context": {
      "icm": "http://tio.models.tmforum.org/tio/v3.4.0/IntentCommonModel",
      "imo": "http://tio.models.tmforum.org/tio/v3.4.0/IntentManagmentOntology",
      "cat": "http://www.operator.com/Catalog",
      "xsd": "http://www.w3.org/2001/XMLSchema",
      "t":   "http://www.w3.org/2006/time"
    },
    "@graph": [
      {
        "@id": "idan:EventLiveBroadcast000001",
        "@type": "icm:Intent",
        "icm:intentOwner": {"@id": "idan:Salesforce"},
        "icm:hasExpectation": [{"@id": "idan:Deliveryservice"}]
      }
    ]
  }
}
```

## TurtleExpression (optional)
```json
{
  "@type": "TurtleExpression",
  "@baseType": "Expression",
  "iri": "https://mycsp.com:8080/tmf-api/rdfs/turtleExpression-example-1",
  "expressionValue": "@prefix icm: <http://tio.models.tmforum.org/tio/v1.0.0/IntentCommonModel> .\n:Intent1 a icm:Intent ..."
}
```

## RDFLib Storage Rule — CRITICAL
```python
import json
from rdflib import Literal, XSD, URIRef, Graph
from rdflib.namespace import RDF

TMF = Namespace("http://tmforum.org/api/v5/")

# JsonLdExpression — serialise dict to JSON string
g.add((expr_uri, TMF.expressionValue,
       Literal(json.dumps(expression_value), datatype=XSD.string)))

# TurtleExpression — store Turtle string as-is
g.add((expr_uri, TMF.expressionValue,
       Literal(expression_value, datatype=XSD.string)))

# On GET — deserialise back
raw = str(g.value(expr_uri, TMF.expressionValue))
# JsonLd: json.loads(raw)  |  Turtle: raw as string
```

**NEVER parse `expressionValue` into RDF triples in this API's graph store.**
The expressionValue is opaque — it belongs to the TIO ontology layer, not this API's store.

## TIO Namespaces
```
icm → http://tio.models.tmforum.org/tio/v3.4.0/IntentCommonModel
imo → http://tio.models.tmforum.org/tio/v3.4.0/IntentManagmentOntology
log → http://tio.models.tmforum.org/tio/v3.4.0/LogicalOperators
xsd → http://www.w3.org/2001/XMLSchema
t   → http://www.w3.org/2006/time
```

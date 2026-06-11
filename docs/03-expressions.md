# TMF921 Expression Types

## Abstract rule

`expression` is abstract and must be extended by a concrete type via `@type`.
Supported concrete types:
- `JsonLdExpression` — mandatory support.
- `TurtleExpression` — optional support.

## Required expression fields

- `@type`
- `@baseType`
- `iri`
- `expressionValue`

## JsonLdExpression

`JsonLdExpression` must be accepted for TMF921 compliance.
`expressionValue` may contain JSON-LD content and must be stored as an opaque string value in the API persistence layer.

## TurtleExpression

`TurtleExpression` may be supported if the generated models and implementation include it.
`expressionValue` must be stored and returned as the original Turtle string.

## Storage rule

The API store must never parse `expressionValue` into RDF triples.
The API persists `expressionValue` as an opaque string literal only.
The intent handler is the only layer that may materialise the expression into a temporary evaluation graph for reasoning or compliance checks.

## TIO namespaces

Typical namespaces used inside expression content include:
- `icm`
- `imo`
- `log`
- `xsd`
- `t`

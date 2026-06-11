# Skill: TMF921 Intent Management

> Requires the base TMF API skill: `tmf-api-guidelines`.

This skill defines the **TMF921-specific** rules for Intent Management API work. Use it together with the generic TMF API guidelines skill whenever the task involves Intent, IntentReport, IntentSpecification, ProbeIntent, intent expressions, negotiation, or TMF921 events. It is based on **TMF921 Intent Management v5.0.0** and the **TMF921B Conformance Profile v5.0.0**. [file:303][file:87]

---

## Scope

Apply this skill for:
- Designing or reviewing TMF921 REST endpoints. [file:303][file:87]
- Building models for `Intent`, `IntentReport`, `IntentSpecification`, and `ProbeIntent`. [file:303][file:87]
- Implementing intent expressions and TIO integration. [file:303]
- Supporting negotiation scenarios such as ProbeIntent and patch-based negotiation flows. [file:303]
- Publishing or consuming TMF921 event notifications. [file:303]

---

## 1. Mandatory resources

TMF921 requires these resources in the API model: [file:87]

| Resource | Purpose |
|---|---|
| `Intent` | Formal description of expectations including requirements, goals, and constraints |
| `IntentReport` | Reports on the status of a given Intent instance |
| `IntentSpecification` | Specification or template for intent instances |
| `ProbeIntent` | Extension of Intent used in negotiation scenarios |

Notes:
- `ProbeIntent` is an extension of `Intent`. [file:303][file:87]
- Extended resources inherit the same operations and notifications as the base resource. [file:303]

---

## 2. Mandatory operations

### Intent
Mandatory operations are: [file:87]
- `GET`
- `POST`
- `PATCH`
- `DELETE`

### IntentReport
Mandatory operations are: [file:87]
- `GET`
- `DELETE`

### IntentSpecification
Mandatory operations are: [file:87]
- `GET`
- `POST`
- `PATCH`
- `DELETE`

### ProbeIntent
There are no separate mandatory operations because ProbeIntent inherits the Intent rules. [file:87][file:303]

---

## 3. Canonical endpoints

```text
GET    /intent
GET    /intent/{id}
POST   /intent
PATCH  /intent/{id}
DELETE /intent/{id}

GET    /intent/{intentId}/intentReport
GET    /intent/{intentId}/intentReport/{id}
DELETE /intent/{intentId}/intentReport/{id}

GET    /intentSpecification
GET    /intentSpecification/{id}
POST   /intentSpecification
PATCH  /intentSpecification/{id}
DELETE /intentSpecification/{id}

POST   /hub
DELETE /hub/{id}
POST   /listener
```

These operations and notification endpoints are defined in the TMF921 specification and its conformance profile. [file:303][file:87]

---

## 4. Intent resource rules

An Intent is the formal description of all expectations including requirements, goals, and constraints given to a technical system. [file:303]

### Mandatory input on create
For `POST /intent`, the following are mandatory: [file:87]
- `name`
- `type`
- `expression`
- `expression.type`
- `expression.iri`
- `expression.expressionValue`

### Mandatory output attributes
In responses, `Intent` must include mandatory attributes required by the conformance profile, including `type`, `href` in response messages, `name`, and the required expression fields. [file:87]

### Common Intent fields
Important first-level fields include: `attachment`, `characteristic`, `context`, `creationDate`, `description`, `expression`, `href`, `id`, `intentRelationship`, `intentSpecification`, `isBundle`, `lastUpdate`, `lifecycleStatus`, `name`, `priority`, `relatedParty`, `statusChangeDate`, `validFor`, `version`, `@baseType`, `@schemaLocation`, and `@type`. [file:303]

---

## 5. Expression rules

TMF921 expressions are central to the API. An Intent has a single expression, and that expression is validated against the TM Forum Intent Ontology, which is RDF-based and can be serialized in JSON-LD, Turtle, RDF/XML, or YAML-LD. [file:303][file:87]

### Critical rule
`IntentExpression` is abstract and must be extended by a concrete subtype. [file:303]

### Supported concrete forms
| Concrete type | Format |
|---|---|
| `JsonLdExpression` | JSON-LD |
| `TurtleExpression` | Turtle |
| `RdfXmlExpression` | RDF/XML |
| `YamlLdExpression` | YAML-LD |

### Mandatory support
`JsonLdExpression` support is mandatory in TMF921 conformance. [file:87][file:303]

### Required expression fields
A concrete expression must include: [file:87][file:303]
- `type`
- `iri`
- `expressionValue`

### Example structure
```json
{
  "expression": {
    "type": "JsonLdExpression",
    "iri": "https://mycsp.com:8080/tmf-api/rdfs/expression-example-1",
    "expressionValue": {
      "@context": {
        "icm": "http://tio.models.tmforum.org/tio/v3.4.0/IntentCommonModel"
      }
    }
  }
}
```

---

## 6. ProbeIntent rules

ProbeIntent is an extension of Intent used in negotiation scenarios. [file:303]

Rules:
- Treat `ProbeIntent` as an `Intent` subtype, not a separate standalone contract. [file:303][file:87]
- ProbeIntent uses the same mandatory create attributes as Intent. [file:87]
- ProbeIntent uses the same patchable and non-patchable rules as Intent. [file:87]
- ProbeIntent inherits the same operations and notifications as Intent. [file:303][file:87]

Negotiation scenarios called out in TMF921 include Probe intent interaction, Judge/preference interaction, and Best/propose interaction. ProbeIntent is modeled directly, while some other negotiation flows are handled by patching an existing Intent. [file:303]

---

## 7. IntentReport rules

An IntentReport reports on the status of a given Intent instance. Intents may have multiple IntentReports. [file:303]

Rules:
- IntentReport has its own expression model and examples in the specification. [file:303]
- `GET` and `DELETE` are the mandatory conformance operations. [file:87]
- Mandatory response attributes include `type`, `href` in response messages, and `name`, plus required nested fields when optional parents are present. [file:87]

Use the nested route structure under the owning intent for access and deletion. [file:303][file:87]

---

## 8. IntentSpecification rules

IntentSpecification defines the specification or template aspects used by the API. [file:303]

### Mandatory create input
For `POST /intentSpecification`, the following are mandatory: [file:87]
- `name`
- `type`

### Mandatory output attributes
Responses must include mandatory attributes such as `type`, `href` in response messages, and `name`, with nested requirements applied when optional parent elements are present. [file:87]

### Patchable vs non-patchable
Non-patchable fields include: [file:87]
- `href`
- `id`
- `lastUpdate`
- `baseType`
- `schemaLocation`
- `type`

Patchable fields include `attachment`, `constraint`, `description`, `entitySpecRelationship`, `expressionSpecification`, `intentSpecRelationship`, `isBundle`, `lifecycleStatus`, `name`, `relatedParty`, `specCharacteristic`, `targetEntitySchema`, `validFor`, and `version`. [file:87]

---

## 9. Patch rules

TMF921 defines explicit patchability rules for Intent and IntentSpecification. [file:87]

### Intent non-patchable fields
Do not patch these fields on Intent: [file:87]
- `creationDate`
- `href`
- `id`
- `lastUpdate`
- `statusChangeDate`
- `version`
- `baseType`
- `schemaLocation`
- `type`

### Intent patchable fields
Intent patchable fields include: [file:87]
- `attachment`
- `characteristic`
- `context`
- `description`
- `expression`
- `intentRelationship`
- `intentSpecification`
- `isBundle`
- `lifecycleStatus`
- `name`
- `priority`
- `relatedParty`
- `validFor`

### Patch application context
The mandatory application context for TMF921 PATCH is JSON Merge. [file:87]

---

## 10. GET, filtering, and fields behavior

TMF921 applies common TMF filtering and attribute selection patterns with specific conformance requirements. [file:87]

Rules:
- `GET /intent` and `GET /intent/{id}` must support attribute selection for all first-level attributes except `href`. [file:87]
- The same rule applies to `IntentReport` and `IntentSpecification` GET operations. [file:87]
- Filtering on sub-resources is optional for all compliance levels. [file:87]
- Filter criteria combine with logical `AND`. [file:87]
- `GET` success code is `200`, and `404` is mandatory for not found singleton retrievals. [file:87]

---

## 11. Notifications

TMF921 defines notification resource models and event types for Intent, IntentReport, and IntentSpecification. [file:303]

### Event types
Use these exact event type names: [file:303]
- `intentCreateEvent`
- `intentDeleteEvent`
- `intentStatusChangeEvent`
- `intentAttributeValueChangeEvent`
- `intentReportCreateEvent`
- `intentReportDeleteEvent`
- `intentSpecificationCreateEvent`
- `intentSpecificationDeleteEvent`
- `intentSpecificationStatusChangeEvent`
- `intentSpecificationAttributeValueChangeEvent`

Rules:
- Event type names are case-sensitive and should not be renamed. [file:303]
- ProbeIntent inherits Intent notifications. [file:303]
- Publish and consume these through the TMF hub/listener pattern. [file:303]

---

## 12. Polymorphism in TMF921

TMF921 explicitly supports polymorphism and schema-based extension using `type`, `baseType`, `referredType`, and `schemaLocation`. [file:303]

Rules:
- Use `type` as the discriminator for actual class type, for example distinguishing `ProbeIntent` from `Intent`. [file:303]
- Use `referredType` in reference entities when the referred target class needs disambiguation. [file:303]
- Use `schemaLocation` to point to user-defined properties or extended schema definitions. [file:303]
- Use `baseType` to declare the parent class of an extended resource. [file:303]

---

## 13. Lifecycle and semantics

The TMF921 specification describes intent lifecycle management and reporting as the foundation of intent-driven operations. Intents communicate requirements to autonomous systems, and IntentReports communicate status back across intent management functions. [file:303]

Rules:
- Treat the expression as the semantic core of the Intent. [file:303]
- Keep resource metadata and intent semantics separate. [file:303]
- Use `lifecycleStatus` and `statusChangeDate` consistently. [file:303]
- Support multiple `IntentReport` resources for a single `Intent`. [file:303]

---

## 14. Implementation checklist

Use this checklist for TMF921 work:

- [ ] Load the base `tmf-api-guidelines` skill first. [file:303][file:87]
- [ ] Implement all four mandatory resources. [file:87]
- [ ] Implement all mandatory operations for Intent and IntentSpecification. [file:87]
- [ ] Use concrete expression subtypes, never raw abstract `IntentExpression`. [file:303]
- [ ] Support `JsonLdExpression` as mandatory. [file:87][file:303]
- [ ] Enforce non-patchable fields on Intent and IntentSpecification. [file:87]
- [ ] Support `fields` on all first-level GET attributes except `href`. [file:87]
- [ ] Use exact TMF921 event type strings. [file:303]
- [ ] Treat ProbeIntent as an Intent subtype that inherits operations and notifications. [file:303][file:87]
- [ ] Preserve ontology-driven meaning in `expressionValue`. [file:303]

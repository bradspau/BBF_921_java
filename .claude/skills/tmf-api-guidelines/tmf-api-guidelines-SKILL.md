# Skill: TMF API Design Guidelines

This skill defines the **generic** TM Forum REST API design rules that apply across TMF APIs. Use it as the base skill for designing, reviewing, generating, or validating TMF-compliant APIs. It is based on **TMF630 REST API Design Guidelines Parts 1–7** and common TMF API conventions. [file:309][file:304][file:307][file:308][file:305][file:306][file:310]

---

## Scope

Apply this skill when working on any TMF API, including but not limited to TMF622, TMF641, TMF666, and TMF921. It covers the uniform contract, URI design, filtering, pagination, errors, notifications, schema structure, polymorphism, versioning, and patch patterns. [file:309][file:304][file:307][file:308][file:305][file:306][file:310]

---

## 1. Uniform contract

| HTTP Verb | Target | Meaning | Success code |
|---|---|---|---|
| `GET` | Collection or singleton | Retrieve resource state | `200` |
| `POST` | Collection | Create a resource | `201` |
| `PATCH` | Singleton | Partial update | `200` |
| `PUT` | Singleton | Full replacement, when supported | `200` |
| `DELETE` | Singleton | Delete resource | `204` |
| `POST` | Task resource | Execute a task action | `200` or `201` |

Rules:
- Use standard HTTP semantics consistently. [file:309]
- Do not tunnel other methods through `GET` or `POST`. [file:303]
- Use `PATCH` for partial modification, not `PUT`. [file:309][file:305]

---

## 2. URI design

Canonical patterns:

```text
/{apiName}/v{major}/{resource}
/{apiName}/v{major}/{resource}/{id}
/{apiName}/v{major}/{resource}/{id}/{subResource}
/{apiName}/v{major}/{resource}/{id}/{subResource}/{subId}
```

Rules:
- Use nouns, not verbs, in URIs. [file:309]
- Use major version only in the path, for example `v5`. [file:309]
- Do not use trailing slashes. [file:309]
- Put identifiers in the path, not in query parameters. [file:309]
- Use task resources for non-CRUD actions such as `exportJob` and `importJob`. [file:308]

---

## 3. Collections, pagination, and filtering

Standard query parameters:
- `offset` for pagination start. [file:309]
- `limit` for page size. [file:309]
- `fields` for attribute projection. [file:309][file:306]
- Simple key-value filters for equality matching. [file:309]
- `filter` for JSONPath-based filtering where supported. [file:306]

Rules:
- Collection `GET` returns an array, including `[]` when empty. [file:309]
- Empty collections return `200`, not `404`. [file:309]
- Multiple filter criteria combine with logical `AND`. [file:87]
- `fields` supports partial response selection. [file:309][file:306]
- Support `X-Total-Count` and `X-Result-Count` headers on collection responses when available. [file:309]

Example:

```http
GET /troubleTicket/v2/troubleTicket?status=Open&fields=id,status,name
```

---

## 4. JSONPath extensions

TMF630 Part 6 extends filtering and field selection with JSONPath for complex nested data access. [file:306]

Rules:
- Use `filter=` for JSONPath collection filtering. [file:306]
- Use `fields=` for partial singleton representations, including nested selection patterns when supported. [file:306]
- Invalid JSONPath syntax in `filter` or `fields` must return `400 Bad Request`. [file:306]
- If JSONPath is unsupported by the server, return `501 Not Implemented`. [file:306]

Example:

```http
GET /api/building?filter=$.building[*].floor[?(@.lift=="working")].apartment[?(@.rooms==1)]
```

---

## 5. Media types and payloads

Rules:
- APIs must support `application/json` by default. [file:306][file:309]
- Use ISO 8601 date-time strings for timestamps. [file:309]
- Include `id` and `href` in addressable resource representations. [file:310]
- Validate `Content-Type` and reject unsupported payload types with `415 Unsupported Media Type`. This is consistent with standard HTTP practice used alongside TMF patterns. [file:309]

---

## 6. Error handling

TMF APIs must use HTTP status codes from the IANA registry and a structured error response model. [file:306][file:309]

Recommended error body:

```json
{
  "code": "ERR001",
  "reason": "Short human-readable summary",
  "message": "Detailed developer-facing explanation",
  "status": "400",
  "referenceError": "https://example.com/errors/ERR001"
}
```

Rules:
- Use standard HTTP status codes. [file:306]
- Return `400` for malformed requests, including invalid JSONPath expressions. [file:306]
- Return `404` for missing singleton resources. [file:87]
- Return `501` when a valid optional capability such as JSONPath is unsupported. [file:306]
- Keep error payloads consistent across the API. [file:309]

---

## 7. Polymorphism and extension

TMF APIs use meta-attributes to support polymorphism and extensibility. [file:304][file:303]

| Attribute | Meaning |
|---|---|
| `@type` | Actual subtype or discriminator |
| `@baseType` | Parent type |
| `@schemaLocation` | URI to schema defining extensions |
| `@referredType` | Actual type of a referenced entity |

Rules:
- Use `@type` when subtype distinction matters. [file:304][file:303]
- Use `@referredType` only in references when the referred target type needs disambiguation. [file:304][file:303]
- Treat `@type`, `@baseType`, and `@schemaLocation` as immutable after creation unless a specification explicitly allows otherwise. [file:87]
- Use polymorphic collections where subtypes inherit base resource operations and notifications. [file:303][file:304]

---

## 8. Notifications and hub pattern

TMF APIs use a standard hub/listener pattern for event subscriptions. [file:304][file:306][file:303]

Canonical endpoints:

```text
POST   /hub
DELETE /hub/{id}
POST   /listener
```

Event envelope example:

```json
{
  "eventId": "eventId",
  "eventTime": "eventTime",
  "eventType": "eventType",
  "event": {
    "resource": {
      "id": "3180",
      "href": "https://host:port/resource/3180"
    }
  }
}
```

Rules:
- Use a consistent event envelope. [file:306][file:304]
- Allow listener registration with callback URL. [file:306][file:303]
- Support filtered hub registration using JSONPath where implemented. [file:306]

---

## 9. JSON Patch and array updates

TMF630 Part 5 defines JSON Patch extensions to manage arrays without relying on numeric indices. [file:305]

Rules:
- Support partial updates with `PATCH`. [file:309][file:305]
- JSON Patch Query uses a query-oriented `path` to target array elements by content instead of position. [file:305]
- MIME type for JSON Patch Query is `application/json-patch-query+json`. [file:305]
- TMF630 Part 6 further extends patch targeting with JSONPath expressions. [file:306]
- It is recommended to use a `test` operation before `replace` or `remove`. [file:306]

Example:

```json
[
  {
    "op": "add",
    "path": "note[?(@.author=='John Doe' && @.status=='Edited')]",
    "value": {"text":"Informed"}
  }
]
```

---

## 10. Task resources

Use task resources for asynchronous or operational workflows that are not plain CRUD. TMF630 Part 4 defines common `ExportJob` and `ImportJob` patterns. [file:308]

Rules:
- Create tasks with `POST` on a task collection or sub-resource. [file:308]
- Poll task status with `GET` on the created task resource. [file:308]
- Use task status values like `notstarted`, `running`, `succeeded`, and `failed`. [file:308]
- Publish completion notifications when relevant. [file:308]

---

## 11. Versioning and lifecycle

Rules:
- API version in the URI is independent from entity `version` inside the payload. [file:308]
- By default, retrieving a singleton returns the current version of a resource. [file:308]
- Admin or privileged APIs may expose multiple entity versions. [file:308]
- PATCH or PUT to `/resource/{id}` acts on the latest version by default unless a version directive is used. [file:308]

Example versioned identifier pattern:

```text
/productOffering/VirtualStorage(Version=1.0)
```

---

## 12. Security and access control

TMF guidance anticipates OAuth2 or OpenID Connect for authorization and role-based access control. [file:308][file:303]

Rules:
- Use HTTPS. This is standard secure API practice aligned with enterprise TMF deployments. [file:303]
- Use OAuth2 or OpenID Connect for authN and authZ. [file:308][file:303]
- Enforce ACL and role-based permissions. [file:308]
- Return `401` for missing or invalid credentials and `403` for authenticated but unauthorized operations. This follows standard HTTP semantics in TMF-style APIs. [file:303][file:308]

---

## 13. JSON Schema design

TMF630 Part 7 defines a consistent JSON Schema structure based on draft-07. [file:310]

Required schema skeleton:

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "$id": "http://datamodel.tmforum.org/[Entity].schema.json",
  "$version": "4.0.0",
  "title": "[Entity]"
}
```

Core patterns:
- `Addressable` has `id` and `href` and must not be changed per API. [file:310]
- `Extensible` has `@type`, `@baseType`, and `@schemaLocation` and must not be changed per API. [file:310]
- `Entity` combines Addressable and Extensible and must be reused via `allOf`. [file:310]
- `EntityRef` combines Addressable, Extensible, `name`, and `@referredType`. [file:310]

Rules:
- One entity per schema file. [file:310]
- Use `allOf` to compose base schemas. [file:310]
- Use draft-07 consistently. [file:310]
- Keep schema names, file names, and titles aligned. [file:310]

---

## 14. OpenAPI documentation rules

Rules:
- Use OAS 3.0 for modern TMF APIs such as TMF921 v5.0.0. [file:87]
- Every operation should document parameters, request bodies, and responses clearly. This matches TMF's model-driven approach. [file:303][file:310]
- Reuse component schemas with `$ref` instead of duplicating inline definitions. This follows TMF schema reuse patterns. [file:310]
- Use `allOf`, `oneOf`, and discriminators for polymorphic resources. [file:304][file:310]

---

## 15. Review checklist

Use this checklist for any TMF API work:

- [ ] URI uses nouns and major version in path. [file:309]
- [ ] HTTP method matches the uniform contract. [file:309]
- [ ] Collection responses are arrays. [file:309]
- [ ] `fields`, `offset`, and `limit` are handled correctly. [file:309][file:306]
- [ ] Error responses are structured and consistent. [file:306]
- [ ] `@type` and related meta-attributes are used correctly for polymorphism. [file:304][file:303]
- [ ] Notifications follow the hub/listener pattern. [file:304][file:306]
- [ ] Schema files use draft-07 and `allOf` composition. [file:310]
- [ ] Versioning and lifecycle semantics are not mixed up. [file:308]
- [ ] Patch behavior is explicit, especially for arrays. [file:305][file:306]

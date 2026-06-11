# TMF921 API Operations

## Intent operations

Mandatory operations:
- `GET /intent`
- `GET /intent/{id}`
- `POST /intent`
- `PATCH /intent/{id}`
- `DELETE /intent/{id}`

## IntentReport operations

Mandatory operations:
- `GET /intent/{intentId}/intentReport`
- `GET /intent/{intentId}/intentReport/{id}`
- `DELETE /intent/{intentId}/intentReport/{id}`

POST and PATCH on IntentReport are not mandatory.

## IntentSpecification operations

Mandatory operations:
- `GET /intentSpecification`
- `GET /intentSpecification/{id}`
- `POST /intentSpecification`
- `PATCH /intentSpecification/{id}`
- `DELETE /intentSpecification/{id}`

## Hub operations

Mandatory operations:
- `POST /hub`
- `DELETE /hub/{id}`

## Query parameters

Mandatory support:
- `?fields=f1,f2` — first-level attribute selection only; `href` always included.
- filtering parameters — logical AND across multiple query parameters.
- `?offset=n` — default 0.
- `?limit=n` — default 20.

List responses must return:
- `X-Total-Count`
- `X-Result-Count`

## Pagination pattern

Use SPARQL `COUNT(*)` for total count and `LIMIT/OFFSET` for page results.

## Health endpoint

`GET /health` returns:
```json
{"status":"UP","graph":"UP|DOWN"}
```
The graph value is based on the Fuseki store health check.

## Error format

Example 404 body:
```json
{"code":"404","reason":"Not Found","message":"Intent {id} not found","@type":"Error"}
```

# TMF921 API Operations

## Intent Operations (all MANDATORY)
| Method | Path | Status Codes |
|---|---|---|
| GET | `/intent` | 200, 404 |
| GET | `/intent/{id}` | 200, 404 |
| POST | `/intent` | 201, 400 |
| PATCH | `/intent/{id}` | 200, 400, 404 |
| DELETE | `/intent/{id}` | 204, 404 |

## IntentReport Operations (GET + DELETE mandatory)
| Method | Path | Status Codes |
|---|---|---|
| GET | `/intent/{intentId}/intentReport` | 200, 404 |
| GET | `/intent/{intentId}/intentReport/{id}` | 200, 404 |
| DELETE | `/intent/{intentId}/intentReport/{id}` | 204, 404 |

> POST and PATCH on IntentReport are NOT mandatory.

## IntentSpecification Operations (all MANDATORY)
| Method | Path | Status Codes |
|---|---|---|
| GET | `/intentSpecification` | 200, 404 |
| GET | `/intentSpecification/{id}` | 200, 404 |
| POST | `/intentSpecification` | 201, 400 |
| PATCH | `/intentSpecification/{id}` | 200, 400, 404 |
| DELETE | `/intentSpecification/{id}` | 204, 404 |

## Hub (Notifications)
| Method | Path | Status Codes |
|---|---|---|
| POST | `/hub` | 201, 409 |
| DELETE | `/hub/{id}` | 204, 404 |

## GET Query Parameters (mandatory support)
| Param | Behaviour |
|---|---|
| `?fields=f1,f2` | Attribute selection — first-level only; `href` always included |
| `?{attr}={val}` | Filtered search — logical AND across multiple params |
| `?offset={n}` | Pagination (default 0) |
| `?limit={n}` | Pagination (default 20) |

**Required response headers:** `X-Total-Count`, `X-Result-Count`

## RDFLib Pagination Pattern
```python
# SPARQL COUNT for X-Total-Count
SELECT (COUNT(?s) AS ?count) WHERE { ?s rdf:type tmf:Intent }

# SPARQL LIMIT/OFFSET for paginated results
SELECT ?s WHERE { ?s rdf:type tmf:Intent } LIMIT 20 OFFSET 0
```

## Health Endpoint
```
GET /health → { "status": "UP", "graph": "UP|DOWN" }
```
Health check: attempt `graph.store.__len__()` — if no exception, graph is UP.

## Error Format
```json
{ "code": "404", "reason": "Not Found", "message": "Intent {id} not found", "@type": "Error" }
```

# TMF921 Notifications

## Hub Endpoints
```
POST   /hub         → 201 { "id": "42", "callback": "http://in.listener.com", "query": "" }
DELETE /hub/{id}    → 204
```
- 409 if implementation does not support multiple listeners and one is already registered
- Fan-out fires **asynchronously** after API response is sent — use `asyncio.create_task()`

## RDFLib Hub Storage
```python
# Store hub registration as triples in default graph
g.add((hub_uri, RDF.type,      TMF.Hub))
g.add((hub_uri, TMF.id,        Literal(hub_id, datatype=XSD.string)))
g.add((hub_uri, TMF.callback,  Literal(callback_url, datatype=XSD.anyURI)))
g.add((hub_uri, DCTERMS.created, Literal(now_utc(), datatype=XSD.dateTime)))
```

## Async Fan-Out Pattern
```python
import asyncio, httpx

async def notify_hubs(cg: ConjunctiveGraph, event_type: str, payload: dict):
    hubs = list_hubs(cg)  # SPARQL SELECT on default graph
    async with httpx.AsyncClient(timeout=5.0) as client:
        tasks = [client.post(hub.callback, json=payload) for hub in hubs]
        results = await asyncio.gather(*tasks, return_exceptions=True)
    # Log failures — never raise, never block API response
```

## eventType Values (all 10)
```
IntentCreateEvent
IntentDeleteEvent
IntentStatusChangeEvent
IntentAttributeValueChangeEvent
IntentReportCreateEvent
IntentReportDeleteEvent
IntentSpecificationCreateEvent
IntentSpecificationDeleteEvent
IntentSpecificationAttributeValueChangeEvent
IntentSpecificationStatusChangeEvent
```

## Notification Payload Structure
```json
{
  "correlationId": "string",
  "description": "string",
  "domain": "string",
  "eventId": "UUID",
  "eventTime": "ISO8601 UTC",
  "eventType": "IntentCreateEvent",
  "priority": 1,
  "timeOcurred": "ISO8601 UTC",
  "title": "string",
  "event": { "intent": { ...full Intent resource... } },
  "reportingSystem": { "id": "...", "name": "...", "@type": "ReportingResource", "referredType": "LogicalResource" },
  "source": { "id": "...", "name": "...", "@type": "ReportingResource", "referredType": "LogicalResource" },
  "@baseType": "Event",
  "@type": "IntentCreateEvent"
}
```

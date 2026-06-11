# TMF921 Notifications

## Hub endpoints

Mandatory hub operations:
- `POST /hub` returns 201 on success.
- `DELETE /hub/{id}` returns 204 on success.
- `POST /hub` may return 409 if the implementation does not support multiple listeners and one is already registered.

## Hub storage

Hub registrations are stored in the authoritative RDF store and retrieved by repository queries.
Each hub stores its identifier, callback URL, and creation timestamp.

## Async fan-out pattern

Notification fan-out fires asynchronously after the originating API response is sent.
Outbound callback delivery must not block the API response.
Notification delivery failures must be logged and must not fail the originating API operation.

## eventType values

The implementation must define the TMF event types, including:
- `IntentCreateEvent`
- `IntentDeleteEvent`
- `IntentStatusChangeEvent`
- `IntentAttributeValueChangeEvent`
- `IntentReportCreateEvent`
- `IntentReportDeleteEvent`
- `IntentSpecificationCreateEvent`
- `IntentSpecificationDeleteEvent`
- `IntentSpecificationAttributeValueChangeEvent`
- `IntentSpecificationStatusChangeEvent`

## Notification payload

Notification payloads must include standard event metadata such as:
- `correlationId`
- `eventId`
- `eventTime`
- `eventType`
- `event`
- `@baseType`
- `@type`

The `event` field must contain the relevant TMF resource object for the emitted notification.

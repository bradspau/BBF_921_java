# PATCH Rules

## Content Types (both must be accepted)
- `application/json` — JSON Merge Patch RFC 7386
- `application/merge-patch+json` — JSON Merge Patch RFC 7386

## Intent — Non-Patchable (return 400 if present in body)
```
id, href, creationDate, lastUpdate, statusChangeDate, version,
@type (immutable), @baseType (immutable), @schemaLocation (immutable)
```

## Intent — Patchable
```
attachment, characteristic, context, description, expression,
intentRelationship, intentSpecification, isBundle, lifecycleStatus,
name, priority, relatedParty, validFor
```

## IntentSpecification — Non-Patchable
```
id, href, lastUpdate,
@type (immutable), @baseType (immutable), @schemaLocation (immutable)
```

## IntentSpecification — Patchable
```
attachment, constraint, description, entitySpecRelationship,
expressionSpecification, intentSpecRelationship, isBundle,
lifecycleStatus, name, relatedParty, specCharacteristic,
targetEntitySchema, validFor, version
```

## ProbeIntent
Same patchable/non-patchable rules as Intent.

## RDFLib PATCH Pattern (DELETE old value, INSERT new value)
```python
# Never DELETE the entire subject — only update changed predicates
def patch_literal(g: Graph, subject: URIRef, predicate: URIRef, new_value: str, datatype=XSD.string):
    g.remove((subject, predicate, None))  # remove old value only
    g.add((subject, predicate, Literal(new_value, datatype=datatype)))

# Always update dcterms:modified after any PATCH
patch_literal(g, intent_uri, DCTERMS.modified, now_utc(), XSD.dateTime)
```

## lifecycleStatus PATCH Rule
```python
# Before updating lifecycleStatus, validate the transition:
current = str(g.value(intent_uri, TMF.lifecycleStatus))
if not LifecycleStatus(current).can_transition_to(LifecycleStatus(new_status)):
    raise ValueError(f"Invalid transition: {current} → {new_status}")
# Then update + record StateChange named graph (see docs/04-state-machine.md)
```

## Sub-resources on PATCH
If PATCH creates new sub-resources, apply same mandatory attribute rules as POST.

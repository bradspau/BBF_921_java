# PATCH Rules

## Content types

Both of the following content types must be accepted for JSON Merge Patch:
- `application/json`
- `application/merge-patch+json`

## Intent non-patchable fields

Return HTTP 400 if any of the following appear in an Intent PATCH body:
- `id`
- `href`
- `creationDate`
- `lastUpdate`
- `statusChangeDate`
- `version`
- `@type`
- `@baseType`
- `@schemaLocation`

## Intent patchable fields

Intent PATCH may update only the fields designated as patchable by the TMF921 ruleset.
Lifecycle changes must also obey the lifecycle state machine rules.

## IntentSpecification non-patchable fields

Return HTTP 400 if any immutable fields appear in an IntentSpecification PATCH body, including:
- `id`
- `href`
- `lastUpdate`
- `@type`
- `@baseType`
- `@schemaLocation`

## ProbeIntent rule

ProbeIntent follows the same patchable and non-patchable rules as Intent.

## Update pattern

PATCH operations must use SPARQL `DELETE/INSERT WHERE` and update only changed predicates.
Never delete an entire subject during PATCH.
Always update `dcterms:modified` after any successful PATCH.

## lifecycleStatus PATCH rule

Before updating `lifecycleStatus`, validate the requested transition against the lifecycle state machine.
If the transition is invalid, return HTTP 400.
If valid, also write the StateChange audit graph and update timestamps.

## Sub-resources on PATCH

If PATCH creates new sub-resources, the same mandatory attribute rules as POST apply.

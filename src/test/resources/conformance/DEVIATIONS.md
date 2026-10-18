# Conformance deviation register

Every place where the corpus expectation is not a plain reading of the TMF specifications, or where the two engines
may legitimately differ. A case directory with an `expected.jena.ttl` must be named here; `DeviationRegisterTest` and
`ConformanceLoader` enforce that.

Convention: a line that is exactly `case: <name>` (optionally a bullet) says the entry applies to that case directory.
Every named case must exist. Entries about a policy or an extension may name existing cases or none.

PDF references: TR290 = Intent Common Model v3.8.0 (TR290A v3.6.0 has matching sections); TR292A/D/E/F = v3.6.0.

## 1. Legacy multi-function set node: Java intersects, the PDF says union

- case: pool-legacy-multifunction
- Java: two set functions on one node (`set:resourcesOfType` and `set:resourcesWithPropertyObject`) are intersected.
- PDF: TR292A says several set functions on one node are combined by union.
- Decision: the rule engine implements the PDF (union) behaviour for this legacy form. Explicit
  `set:intersection` (used by the seed and by `pool-explicit-intersection`) works the same in both engines.
- Files: `expected.ttl` (Java: member w2) and `expected.jena.ttl` (union: pool w1, w2, g0; member w1).

## 2. Container argument of `set:resourcesWithPropertyObject` (D1): project extension

- case: pool-depends-on-choice
- case: seed-fulfilled
- case: seed-partial
- case: seed-no-free-uni
- case: seed-reevaluation-held
- Java: a container or target argument (for example `ex:SelectedSVLAN`) stands for its members, so the C-tag pool can
  depend on the S-tag choice.
- PDF: TR292F §8.17 matches the remaining arguments literally as objects and does not provide this. Read strictly the
  pool would be empty and the C-tag expectation and intent would be `false`.
- Decision: this is an extension, not specification behaviour. It is kept in both engines and documented here. No
  `expected.jena.ttl`.

## 3. Reporting expectation is always `true` (D2): handler assumption

- case: seed-fulfilled
- case: seed-partial
- case: seed-no-free-uni
- case: seed-reevaluation-held
- Java: `bbf:HSIMonitoringExpectation rdf:value true`.
- PDF: TR290 §3 [A §8] makes compliance depend on all required reports being generated and delivered; TR292A §4's
  default for an element with nothing determining its value would be `false`.
- Decision: the handler cannot see report delivery and assumes it succeeded. The rule engine must reproduce this. No
  `expected.jena.ttl`.

## 4. `icm:reason` on plain violations (D3): design widened

- case: quantity-violated
- Java: a condition that is `false` only because the value is out of bounds carries
  `icm:reason "80 Mbps >= 100 Mbps is false"`.
- Design: §5.2 listed reasons only for errors and failed choices. TR290 §4.5.2 allows a reason on any result.
- Decision: design §5.2 is widened to cover failed comparisons. `expected.ttl` is kept. The rule engine must emit
  identical text: `<observed> <unit> <op> <bound> <unit> is false`. No `expected.jena.ttl`.

## 5. Accepted input extensions (D4)

- case: input-nonlist-resourcesOfType
- case: no-intent-metric-only
- case: quantity-ok
- Non-list `set:resourcesOfType ex:Widget` as shorthand for `( ex:Widget )` (flagged case
  `input-nonlist-resourcesOfType`; other cases use the list form).
- Legacy type form `quan:quanatMost` with `rdf:first`/`rdf:rest` (`no-intent-metric-only`, alongside a TIO-form
  condition).
- Bare metric IRI as the first quantity argument instead of `met:lastValue ( ... )`. This one is legitimate: TR290
  §2.2.2 itself uses a bare metric.
- Decision: results do not change; the rule engine accepts these forms. No `expected.jena.ttl`.

## 6. Handler policy: choice order and reason wording

- case: delivery-chooses-lowest
- case: delivery-wrong-type
- The PDFs do not define an order for `icm:chooseFrom` (TR290 §2.5.3 allows any subset). The handler picks the
  lexically-lowest candidate IRI.
- Exact `icm:reason` wording (for example "no observation: BandwidthMetric", "no candidate of type Widget in
  chooseFrom pool", "unit mismatch: ...") is a cross-engine contract.
- Decision: both engines follow this policy. No `expected.jena.ttl`.

## 7. `log:allOf ( )` is false (Task 13; design §5.5.1)

- case: logic-empty-allof
- Java: `allMatch` over an empty list, so `log:allOf ( )` is `true` (`TurtleEvaluator.applyCombinator`).
- PDF: TR292E §6.1 "If the list of arguments is empty the result is also false". Empty `anyOf` (§6.2) and `oneOf`
  (§6.11) are false and empty `noneOf` (§6.10) is true; both engines agree on those three.
- Decision: the rule engine follows the PDF. `expected.jena.ttl`: `ex:EmptyAll`, `ex:Expectation1` and `ex:Intent`
  are `false`.

## 8. A condition with no properties determining its value is false (Task 13; design §5.5.1)

- case: logic-bare-condition
- Java: a typed `log:Condition`/`icm:Condition` with no function is "unknown/opaque" and passes (`true`).
- PDF: TR292E §4 (page 14) "A condition without properties that determine its value would be assumed to have the
  value false by default"; TR292A §4 gives the same default for the associated value.
- Decision: the rule engine follows the PDF. `expected.jena.ttl`: `ex:BareLog`, `ex:BareIcm`, `ex:Expectation` and
  `ex:Intent` are `false`.

## 9. Literal boolean members of a logical operator are arguments (Task 13; design §5.5.1)

- case: logic-literal-members
- Java: list members that are literals are dropped before combining (`filter(RDFNode::isResource)`), so
  `allOf ( <true> false )` is `true` and `anyOf ( <false> true )` is `false`.
- PDF: TR292E §2 "All arguments of the function also need to be of type boolean"; §6.1, §6.2, §6.10, §6.11
  "Argument types: xsd:boolean". A literal `"false"^^xsd:boolean` is such an argument.
- Decision: the rule engine counts boolean literals; other literals are removed as non-boolean (§2).
  `expected.jena.ttl`: `ex:AllWithFalse` `false`, `ex:AnyWithTrue` `true`.

## 10. An untyped member with no value is removed, not counted as true (Task 13; design §5.5.1)

- case: logic-untyped-member
- Java: a member that is neither a known element type nor has a function is "unknown/opaque" and counts as `true`.
- PDF: TR292E §2 "If arguments are provided that have a different type than boolean ... the respective argument would
  be ignored. This means the evaluation would be performed with an argument list from which all non-boolean
  arguments are removed." A node with no type and no value has no boolean value. Reading it as non-boolean is the
  design's interpretation (§5.5.1); the PDF does not discuss valueless untyped members explicitly.
- Decision: the rule engine removes it. `expected.jena.ttl`: `ex:AnyWithUnknown` `false` (`anyOf ( false )`),
  `ex:OneWithUnknown` `true` (`oneOf ( true )`).

## 11. A list whose members are all opaque is not an error (Task 13; design §5.5.1)

- case: logic-opaque-noneof
- Java: when every member is opaque the operator fails (`opaqueChildren`) with the reason "no evaluable conditions in
  children", so `noneOf ( ex:Unknown1 ex:Unknown2 )` is `false`.
- PDF: with the members removed as in entry 10 (TR292E §2) the list is empty, and `noneOf ( )` is `true` (§6.10).
- Decision: the rule engine follows the PDF; no reason. `expected.jena.ttl`: `ex:NoneOfUnknown`, `ex:Expectation`
  and `ex:Intent` are `true`, without `icm:reason`.

## 12. `log:matchStatement` takes rdf:Statement arguments (Task 13)

- case: logic-matchstatement
- Java: reads `log:matchStatement ( s p o ctx )`, a flat list of at least four nodes; the form in the PDF (one
  argument, a statement node) fails with the reason "expected 4 args (s p o ctx)".
- PDF: TR292E §3 (example) and §6.9: the arguments are instances of `rdf:Statement` (`rdf:subject`, `rdf:predicate`,
  `rdf:object`); the function is true iff all of them are true in the knowledge base, false when there is none.
- Decision: the rule engine implements the PDF form only. `expected.jena.ttl`: `ex:GoldUser`, `ex:Expectation` and
  `ex:Intent` are `true`, without `icm:reason`.

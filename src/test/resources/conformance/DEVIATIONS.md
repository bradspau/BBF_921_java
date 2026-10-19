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
- PDF: TR292E §2 "If arguments are provided that have a different type than boolean an error would be issued and
  the respective argument would be ignored. This means the evaluation would be performed with an argument list from
  which all non-boolean arguments are removed." A node with no type and no value has no boolean value. Reading it as
  a non-boolean argument is the design's interpretation (§5.5.1); the PDF does not discuss valueless untyped members
  explicitly.
- Decision: the rule engine removes it. `expected.jena.ttl`: `ex:AnyWithUnknown` `false` (`anyOf ( false )`),
  `ex:OneWithUnknown` `true` (`oneOf ( true )`).
- The error the PDF says is issued for the ignored argument is not surfaced as an `icm:reason`. That is a project
  policy, not the PDF's ruling: design §5.2 attaches reasons only to elements that are `false`, and the PDF defines no
  vocabulary for such an error.

## 11. A list whose members are all opaque: the operator still evaluates (Task 13; design §5.5.1)

- case: logic-opaque-noneof
- Java: when every member is opaque the operator fails (`opaqueChildren`) with the reason "no evaluable conditions in
  children", so `noneOf ( ex:Unknown1 ex:Unknown2 )` is `false`.
- PDF: TR292E §2 "an error would be issued and the respective argument would be ignored. This means the evaluation
  would be performed with an argument list from which all non-boolean arguments are removed." With both members
  removed (entry 10's reading) the list is empty, and `noneOf ( )` is `true` (§6.10). The PDF issues an error per
  ignored argument but still evaluates the operator; it does not make the operator fail.
- Decision: the rule engine follows the PDF for the value. `expected.jena.ttl`: `ex:NoneOfUnknown`, `ex:Expectation`
  and `ex:Intent` are `true`.
- No `icm:reason` is emitted for the ignored arguments. As in entry 10 this is a project policy (design §5.2 attaches
  reasons only to `false` elements), not the PDF's ruling.

## 12. `log:matchStatement` takes rdf:Statement arguments (Task 13)

- case: logic-matchstatement
- Java: reads `log:matchStatement ( s p o ctx )`, a flat list of at least four nodes; the form in the PDF (one
  argument, a statement node) fails with the reason "expected 4 args (s p o ctx)".
- PDF: TR292E §3 (example) and §6.9: the arguments are instances of `rdf:Statement` (`rdf:subject`, `rdf:predicate`,
  `rdf:object`); the function is true iff all of them are true in the knowledge base, false when there is none.
- Decision: the rule engine implements the PDF form only. `expected.jena.ttl`: `ex:GoldUser`, `ex:Expectation` and
  `ex:Intent` are `true`, without `icm:reason`.

## 13. Several functions on one element combine by conjunction (Task 13)

- case: logic-conjunction
- Java: `evalNodeCore` evaluates only the first logical operator it finds on a node, in the order allOf, anyOf,
  noneOf, oneOf, and returns; any other operator or function on the same node is ignored.
- PDF: TR292E §4 "If multiple properties or functions are used within a condition, they are by default in a logical
  conjunction. The truth value of the condition gets true if and only if all contributing properties provide the
  value true as well." TR292A §4: boolean associated values default to "false"; "Default combination method is
  logical conjunction equivalent to the log:allOf function."
- Decision: the rule engine combines all contributions by conjunction. `expected.jena.ttl`: `ex:AllAndNone`
  (`allOf ( true )` and `noneOf ( true )`) and `ex:AnyAndMatch` (`anyOf ( false true )` and a false `log:match`) are
  `false`, and so are `ex:Expectation` and `ex:Intent` (Java: all `true`).

## 14. Boolean set functions in the function form are evaluated (Task 14)

- case: set-boolean-functions
- Java: `set:isMember`, `elementOf`, `empty`, `includedIn`, `intersectsWith` and `forAll` are read only in a legacy
  type form (`a set:setisMember`, ... with a precomputed `rdf:value`, or `a set:setforAll ; rdf:first ...`); in the
  function form `?c set:isMember ( x C )` a condition is "unknown/opaque". All children of the expectation are opaque,
  so it fails with "no evaluable conditions in children" and the conditions get no value. Java also has no
  `set:resourcesWithProperty`, `set:valuesOfObjectProperty` or `set:identical`.
- PDF: TR292F §5 and §8.2-8.5, §8.7, §8.8 define each function's boolean result over the members of its argument
  containers (TR292C §2 function form); §4 and §8.16, §8.20 define the two constructors. `set:intersectsWith` follows
  the §5 example (the first container shares a member with each of the others: "It does not matter that
  ex:UserGroup2 and ex:UserGroup3 do not have common members"), which §8.7's "members in all other containers" is
  read with.
- Decision: the rule engine implements the PDF. `expected.jena.ttl`: `ex:IsGold`, `ex:InRegion`, `ex:Included`,
  `ex:Intersects`, `ex:AllAvailable` `true`; `ex:NotGold`, `ex:NoneEmpty`, `ex:Hot`, `ex:Expectation`, `ex:Intent`
  `false`, without `icm:reason` (the PDF defines no reason for a plain false result).
- Rule-engine limits (sound, fail closed, not specification behaviour): a boolean set function whose container is
  decided later (a delivery target filled by a choice, a `set:difference` result or a pool built on one) is `false`
  with the failure "container membership is not final ..." (tio_a_sets.rules header); `set:forAll` copies its
  condition per member and is `false` when a condition argument has no function.

## 15. A set node keeps its explicit members (Task 14)

- case: pool-explicit-member
- Java: a node that already has an `rdfs:member` is taken as "already materialised" (`materialiseSet`), so its set
  functions are ignored: the pool is only `ex:z9` (a gadget), nothing of type `ex:Widget` can be chosen and `ex:E` is
  `false` ("no candidate of type Widget in chooseFrom pool").
- PDF: TR292F §3, examples of `set:union` and `set:intersection`: the container gains the function's members "next to
  ex:RBS4, which is explicitly added"; the explicit members and the function's members are combined.
- Decision: the rule engine implements the PDF (explicit members are lifted to the pool's members alongside the
  functions'). `expected.jena.ttl`: pool `ex:w1`, `ex:w2`, `ex:z9`; `ex:T rdfs:member ex:w1`; `ex:E` and `ex:Intent`
  `true`. The choice itself is Task 15's; the pool membership is tested by `JenaSetRulesTest`.

## 16. The delivery type includes its subclasses (Task 15)

- case: delivery-subclass-type
- Java: a candidate or target member counts for `icm:deliveryType ex:Widget` only with `rdf:type ex:Widget` itself
  (`TurtleEvaluator.sortedMembers`, `g.contains(m, RDF.type, type)`), so an instance of a subclass is not chosen:
  "no candidate of type Widget in chooseFrom pool".
- PDF: TR290 §2.5.1 "the delivered resource shall be an instance of the specified class"; with
  `ex:BigWidget rdfs:subClassOf ex:Widget` an instance of `ex:BigWidget` is an instance of `ex:Widget` (RDFS). The
  set functions already read classes this way in both engines (`set:resourcesOfType`, TR292F §8.15).
- Decision: the rule engine counts subclass instances (`tmf:instanceOf`, tio_a_delivery.rules), for choices, held
  resources and the expectation's value. `expected.jena.ttl`: `ex:T rdfs:member ex:b1`; `ex:E`, `ex:Intent` `true`.

## 17. Choices do not depend on the order of the expectations (Task 15)

- case: delivery-choice-order
- Java: expectations are evaluated in list order and each chooses when it is evaluated; a pool that depends on another
  expectation's choice (the C-tag pool on the chosen S-VLAN, entry 2) is empty if that expectation comes later, so
  the C-tag expectation is `false` ("no candidate of type CTag in chooseFrom pool"). The seed lists the S-tag
  expectation first for this reason.
- PDF: TR290 §2.3.3 the members of a target "are rather the logical result of the knowledge present"; the PDFs give
  the order of a `log:allOf` list no meaning for evaluation (TR292E §6.1).
- Decision: the rule engine chooses in strata by dependency level (design §5.5, tio_c_choose.rules), whatever the
  order. `expected.jena.ttl`: S-VLAN `ex:svA`, C-tag `ex:b1`; `ex:CTagE`, `ex:STagE`, `ex:Intent` `true`.
- See entry 18 for expectations that share a target: the same order-independence, one choice from their pools' union.

## 18. Expectations sharing a target: one choice from the union of their pools (Task 15 fix round)

- case: delivery-shared-target
- Java: expectations are evaluated in list order (entry 17); the first one that needs the type chooses the lowest
  candidate of its own pool (`ex:w2` from `ex:P1`) and the second finds the target satisfied.
- PDF: TR290 §2.5.3 leaves the choice to the handler ("Any subset can be chosen"); the order of a `log:allOf` list has
  no evaluation meaning (entry 17).
- Decision (owner ruling, Task 15 review): the rule engine chooses once per (target, delivery type) and choice stratum,
  the lowest candidate of the union of the pools of every expectation that needs that type on that target. One resource
  satisfies all of them (so allocation write-back claims one), whatever their order: the order-independence of
  entry 17 applied to a shared target. `expected.jena.ttl`: `ex:T rdfs:member ex:w1`; `ex:E1`, `ex:E2`, `ex:Intent`
  `true`.

## 19. Several targets or delivery types on one expectation (Task 15 fix round)

- case: delivery-multi-target-type
- Java: reads one `icm:target` and one `icm:deliveryType` (`objectResource`, the first statement the model returns);
  the others are ignored. In the case it fills `ex:T2` only and checks `ex:E2` against `ex:Gadget` only.
- PDF: TR290 §2.4/§2.5.1 give no cardinality for `icm:target` or `icm:deliveryType`; by TR292E §4 every property that
  determines the value contributes, by conjunction.
- Decision: the rule engine keeps its behaviour: one contribution per (target, type), combined by conjunction; each
  target gets a member of each type. `expected.jena.ttl`: `ex:T1` and `ex:T2` get `ex:w1`, `ex:T3` gets `ex:w3`;
  `ex:E1` `true`, `ex:E2` `false` (no Gadget), `ex:Intent` `false`.

## 20. Members a target gets from a set function do not count as delivered (Task 15 fix round)

- case: delivery-function-target
- Java: a target with a set function is materialised (`normalizeSetPredicates`), so its computed members are target
  members and `ex:E` is `true` without any choice.
- PDF: TR290 §2.3.3 says target members are "the logical result of the knowledge present", which reads in Java's
  favour; §2.5.3 describes delivery as choosing from `icm:chooseFrom` and adding the choice to the target.
- Decision (owner ruling, Task 15 review): the rule engine keeps its behaviour: only `rdfs:member` of the target (input,
  held or chosen) counts for a delivery expectation. `expected.jena.ttl`: `ex:E`, `ex:Intent` `false` ("no target member
  of type Widget").

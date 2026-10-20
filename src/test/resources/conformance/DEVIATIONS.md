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
- Seen live (Task 17): `AccessDemoIT` wrote its pools in this legacy form (`set:resourcesOfType pon:UNIPort ;
  set:resourcesWithPropertyObject ( pon:inUse false ) ; set:resourcesWithPropertyObject ( pon:operationalState pon:Up )`)
  with no `icm:Intent`: Java selected a free UNI port, the rule engine the lowest IRI of the union, a C-tag
  (`pon:CTAG-N-001`). Compare mode now logs such handler-result differences too. The IT was rewritten (Task 17 fix
  round) with explicit `set:intersection` and `icm:deliveryType pon:UNIPort`, as the seed and the demos write it, and
  asserts that exactly one UNI port is allocated; both engines then agree. The legacy form stays as registered here.

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

## 18. Expectations sharing a target: one choice per group and stratum (Task 15 fix round; wording Task 16)

- case: delivery-shared-target
- Java: expectations are evaluated in list order (entry 17); the first one that needs the type chooses the lowest
  candidate of its own pool (`ex:w2` from `ex:P1`) and the second finds the target satisfied.
- PDF: TR290 §2.5.3 leaves the choice to the handler ("Any subset can be chosen") and says it is made "according to
  the type and number of resources suitable for meeting the requirements associated with the target through
  expectations the target is assigned to": the choice belongs to the target and its type, not to one expectation. The
  order of a `log:allOf` list has no evaluation meaning (entry 17).
- Decision (owner ruling, Task 15 review): the rule engine chooses once per (target, delivery type), whatever the order
  of the expectations. As built (tio_c_choose.rules `poolChoice`, builtin `prevLowestInPools`): choice stratum n reads
  only the pools of the group whose level is n-1, so the choice is the lowest candidate of the group's pools of the
  **earliest stratum that has a candidate**, not the lowest candidate of the union of all the group's pools: once the
  target has a member of the type, later strata choose nothing for it. Within one stratum it is the lowest candidate
  of the union of that stratum's pools. One resource satisfies every expectation of the group (so allocation
  write-back claims one), and an expectation whose own pool is empty is `true` when a sibling's pool supplied the
  resource (its value is "the target has a member of the type", tio_b_delivery.rules). `expected.jena.ttl`:
  `ex:T rdfs:member ex:w1`; `ex:E1`, `ex:E2`, `ex:Intent` `true`.

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

## 21. The specification form `[ met:lastValue ( metric ) ]` (Task 16)

- case: quantity-lastvalue-spec-form
- Java: does not read `met:lastValue` in the function form (it knows only the legacy `a met:metlastValue ; rdfs:member`
  form). The first argument is an anonymous node without value, so the condition is `false` with
  "no observation: null" even when an observation exists (`ex:BandwidthCondition`, observed 150 Mbps), and without
  one the reason does not name the metric.
- PDF: TR292G §6.1.2 / §8.1.2 "The function met:lastValue refers to the newest observed value for a metric and provides
  the value as result", with the example `quan:smaller ( [ met:lastValue ( ex:sliceLatency ) ] [ rdf:value "10" ;
  quan:unit "ms" ] )`; TR292C §4: the subject of `met:lastValue` is a quantity with the result's value and unit.
- Decision: the rule engine implements the PDF (tio_a_quantity.rules `quanArgLastValue`), links the observation it used
  (`met:observed`) and names the metric when there is none. `expected.jena.ttl`: `ex:BandwidthCondition` `true` with
  `met:observed ex:obs-bw`; `ex:LatencyCondition` `false`, "no observation: LatencyMetric".

## 22. Reason text for an anonymous first argument: "no observation: ?" (Task 16)

- case: quantity-anonymous-argument
- Java: the reason is `"no observation: " + localName(uri)`; for a blank node the URI is null, so the text is
  "no observation: null" (`TurtleEvaluator.evalTwoArg`), a defect, not a wording choice.
- PDF: defines no text (design §7 leaves wording to the builder, the same text in both engines being the contract).
- Decision: not reproduced. The rule engine writes "no observation: ?" for an argument that has no name (a blank node,
  or a literal where a metric is expected), as its rules always have (tio_a_quantity.rules `quanArgNameOther`).
  `expected.jena.ttl`: the same graph with "no observation: ?". Applies equally to the spec form of entry 21 when its
  metric is itself anonymous.

## 23. A bare metric as the bound (Task 16)

- case: quantity-metric-bound
- Java: resolves a bare metric through its newest observation only in the first argument (`resolveMetricRefs`
  pattern A); a metric as the second argument has no value, so `quan:atLeast ( ex:BandwidthMetric
  ex:MinimumBandwidthMetric )` is `false` with "missing bound value" (and, returning early, links no observation).
- PDF: every argument of the quantity comparisons is a `quan:Quantity` (TR292D §5.2 "All arguments are quantities";
  §8.1, §8.2, §8.5, §8.6, §8.9, §8.19 "Argument types"); a bare
  metric standing for its last value is the accepted extension of entry 5 (TR290 §2.2.2 uses one). The PDFs give
  argument positions no different meaning.
- Decision: the rule engine applies entry 5's reading to every argument (tio_a_quantity.rules `quanArgMetric`, per
  argument cell). It links only the first argument's observation (`met:observed`, design §5.2). `expected.jena.ttl`:
  150 Mbps >= 100 Mbps, so `ex:BandwidthCondition`, `ex:Expectation`, `ex:Intent` `true`, with `met:observed ex:obs-bw`.

## 24. `quan:exactly` with more than two arguments (Task 16)

- case: quantity-exactly-nary
- Java: evaluates `quan:exactly` like the two-argument comparisons and compares only the first two arguments, so
  `quan:exactly ( 5 5 6 )` is `true`.
- PDF: TR292D §8.5 "evaluated as "true" if the quantity of all arguments are the same", "Arity: at least two
  arguments" (also §5.2 "if the values of all arguments are exactly the same").
- Decision: the rule engine requires all arguments to be equal (tio_a_quantity.rules section 4). With several
  failures or unequal pairs it reports one reason, the lexically first text (tio_a_reasons.rules; an error before a
  violation), which is Java's wherever Java and the PDF agree (`ex:FirstTwoDiffer`: "5 == 6 is false" in both).
  `expected.jena.ttl`: `ex:FirstTwoEqual` `false` with "5 == 6 is false".

## 25. Nested quantity with a unit on both levels: the inner unit counts (Task 16)

- case: quantity-nested-unit
- Java: reads the unit of an argument node outer level first (`TurtleEvaluator.unitOf`), so the bound
  `[ rdf:value [ rdf:value 100 ; quan:unit "Mbps" ] ; quan:unit "Gbps" ]` is 100 Gbps and 150 Mbps >= it is false
  ("150 Mbps >= 100 Gbps is false"). For an observation Java reads the inner unit first (`observedOf`), as the contract
  copy does (`ResultContract.copyObservation`).
- PDF: TR292C §4: a node whose `rdf:value` is a quantity "inherits the properties of the function result ... the
  rdf:value and potentially the quan:unit"; the value and its unit are the inner node's. The PDFs do not discuss a
  conflicting outer unit.
- Decision: the rule engine reads a nested quantity's own unit first and the outer one only when the inner has none, for
  arguments and observations alike (tio_a_quantity.rules `quanNestedUnit`, `quanNestedOuterUnit`). `expected.jena.ttl`:
  150 Mbps >= 100 Mbps, so the condition, `ex:Expectation` and `ex:Intent` are `true`, no reason.
- Not a deviation: surrounding spaces in a unit. Both engines trim units for comparison and in the reason text
  ("80 Mbps >= 100 Mbps is false" for `" Mbps "`) and copy the observation's unit literal unchanged
  (`quantity-unit-whitespace`, no `expected.jena.ttl`).

## 26. Where a reason goes: the nearest named element, only when it is false (Task 16)

- case: reason-attachment
- Java: an element's reasons are the failed details of its own subtree, collected only when it has no named (IRI) child
  element (`TurtleEvaluator.evalNode`: `children.isEmpty() ? r.conditions() : List.of()`), and written whenever a
  detail failed, whatever the element's own value (`ResultGraphWriter`). So the failing anonymous member of
  `ex:Mixed` (which also has the named child `ex:Named`) is reported nowhere, and `ex:TrueWithFailingMember`
  (`anyOf ( failing , true )`, `true`) carries "no observation: LatencyMetric".
- PDF: TR290 §4.2 reports only on IRI-identified elements; TR290 §4.5.2 `icm:reason` explains a result. Design §5.2
  puts reasons on elements that are `false`.
- Decision: the rule engine reports a failure of an anonymous condition on the nearest named element above it, through
  any number of anonymous operators, whether or not that element also has named children, and only when that element
  is `false`; observation links follow the same path, whatever the truth value (tio_b_reasons.rules). Where every
  child is anonymous (the common Java case, e.g. a condition `log:allOf ( [ quan:... ] )`) both engines agree.
  `expected.jena.ttl`: `ex:Mixed` gets "80 Mbps >= 100 Mbps is false" and `met:observed ex:obs-bw`;
  `ex:TrueWithFailingMember` has no reason.

## 27. Observation links: two-argument comparisons and `quan:exactly` only (Task 16; design §5.2 narrowed)

- Design §5.2 says `met:observed` is written for "a condition whose first argument is a metric". The Java engine links
  the observation for `quan:atLeast/atMost/greater/smaller/exactly` once both operands have values (also for a unit
  mismatch), not for `quan:inRange` and not when the comparison stopped at a missing value.
- Decision: both engines do the same (tio_a_reasons.rules `observedBinary`, `observedExactly`); the corpus and
  `JenaReasonsRulesTest` cover it. Design §5.2 is read with this narrowing. No case of its own.

## 28. Expression without an IRI-identified intent: empty contract, the Java engine's handler result (Task 16)

- case: no-intent-metric-only
- case: demo-hsi-inline-good
- case: demo-hsi-inline-bad
- case: demo-seed-probe
- case: demo-access-top
- case: intent-blank-node
- case: intent-subclass
- Java: writes the contract only when an IRI subject has `rdf:type icm:Intent` itself (`ResultGraphWriter` needs an
  element of kind "Intent", which `kindOf` gives only for the exact type; `ReportBuilder` reads the intent the same
  way). Its handler result does not depend on an intent: the state is the conjunction of its evaluation roots
  (`log:allOf/anyOf/noneOf/oneOf` subjects that are no operator's member, and `log:match*` subjects), or, without any
  root, of every condition its flat scan finds (quantity subjects by function, delivery and property expectations);
  without either, Degraded "No quantity conditions found in expression". The demo expressions (docs/HSI_DEMO.md steps
  9 and 10, docs/Access_HSI_Demo.md step 9 `bbf:Top`, the seed_data/seed.sh probe intents `_:c quan:atLeast ...`) have no
  intent at all; a blank-node intent (`[] a icm:Intent`) and an instance of a subclass of `icm:Intent` give no contract
  either.
- Decision (policy, Review Focus 1): no deviation. The rule engine always evaluates; its contract is empty unless an
  IRI subject is typed `icm:Intent` exactly (`ResultAdapter.hasIriIntent`), so no TR290 report is built, as with the
  Java engine. Its handler result is built from the inferred model with the Java engine's rule (`ResultAdapter`): the
  same state, reason string, condition list and Flow 3 proposal as the Java engine in all seven cases
  (`ResultAdapterTest`; `no-intent-metric-only/handler.txt`). With an IRI intent the state is the intent's value
  (entry 29).
- Reading an instance of a subclass of `icm:Intent` as no intent matches the Java engine and `ReportBuilder`; the PDFs'
  RDFS reading would make it an intent. Kept for parity (no `expected.jena.ttl`); revisit with the report builder.

## 29. With an IRI intent the state is the intent's value; Java ANDs every evaluation root (Task 17)

- case: intent-extra-root
- Java: the handler state is the conjunction of every evaluation root (`log:allOf/anyOf/noneOf/oneOf` subjects that
  are no operator's member, and `log:match*` subjects), whether or not the root belongs to the intent.
- Rule engine: with an IRI subject typed `icm:Intent` (entry 28) the state is that intent's value
  (`ResultAdapter`); roots outside the intent are still evaluated and listed, but do not decide the state.
- PDF: TR290 §4.2 reports on the intent: its fulfilment is the value of the intent element and its expectations, not
  of unrelated statements in the same graph.
- The two differ only when an evaluation root lies outside the intent, which no seed, demo or integration-test
  expression has. Case `intent-extra-root`: an intent that holds plus `ex:X log:allOf ( ex:D )` with `ex:D` false:
  Java Degraded, rule engine Fulfilled. The contract graphs agree (no `expected.jena.ttl`); the handler results are in
  `handler.txt`.

## 30. Order of several evaluation roots in the condition list (Task 17)

- case: intent-extra-root
- Java: roots in model iteration order per property (`listSubjectsWithProperty`, hash order inside Jena's graph);
  the rule engine (`ResultAdapter`): per property in IRI order, blank nodes last.
- PDF: no order is specified; the condition list is the handler's own (it feeds the report reason text and Flow 3).
- Only the order of the handler's condition list (and so of the parts of the reason text) differs, never a value.
  With one root (every seed, demo and integration-test expression) the orders agree. The case's `handler.txt` pins the
  Java order as Jena 4.10 gives it; a Jena upgrade may change that line without any behaviour change.

## Not deviations (documented, kept out of the corpus)

- An untyped IRI subject of a quantity function (the demo shape `bbf:DL_Check quan:atLeast ( ... )`) is a reported
  condition in both engines: its own value, reason and observation link (Java `kindOf`; tio_a_quantity.rules
  `quanSubjectElement*`). Case `condition-untyped-iri`, no `expected.jena.ttl`.
- The handler's condition list (ResultAdapter, Task 16 fix round) has one entry per function application, with its own
  value, anonymous and untyped conditions included, as the Java engine's. Where the Java engine evaluates only one of
  several functions on a node (entry 13), the rule engine lists each with its own value; the cases with a registered
  deviation carry their reviewed handler result in `handler.txt`.

- `quan:inRange ( value lower upper )`: both engines read the arguments in this order (TR292D §8.9) and compare
  lower <= value <= upper inclusively; only the reason differs from the two-argument functions (none for a plain
  violation, entry 27).
- Observations with the same `met:obtainedAt`: the Java engine keeps whichever it met first (model order), the rule
  engine the lexically lowest observation IRI (JenaBuiltins `newest`). Neither is specified (TR292G §6.1.2 "newest");
  the corpus avoids equal times.


# Selectable evaluation engines with a shared result graph

Status: draft for approval. Beads: `bbf921j-4qm` (P1).
Date: 2026-10-18.

## 1. Intent and why

The TIO ontology (the Turtle files in `ontology/`, specified by the TM Forum PDFs in `docs/spec/`) is meant to
define what an intent *means*, and Jena rules are meant to carry that meaning out: what `log:allOf`,
`quan:smaller`, `set:resourcesOfType` or a delivery expectation evaluate to. Today none of that happens in
rules. The rule reasoner is loaded at startup and never called; every compliance decision is made by
`TurtleEvaluator`, about 1,100 lines of hand-written Java that re-implements the semantics.

That has three costs: the Java can drift from the specification, a new extension model needs Java code instead of
rules and data, and nothing proves the two agree.

The aim is **two interchangeable evaluation engines**, a Java one (exists, works) and a Jena-rule one (to be built),
that both produce the **same result graph**, so everything downstream is engine-agnostic and the two can be
compared triple for triple. The Java engine becomes the oracle and fallback; the rule engine becomes the intended
implementation and takes over only when it passes the same tests.

## 2. Goals, non-goals, anti-goals

Goals
- Choose the engine at startup: `intent.evaluator=java|jena`. Default `java`.
- Both engines write a result graph in one vocabulary defined by the TM Forum PDFs.
- The handler (not the engine) turns that graph into the TR290 intent report, the intent handling state, the
  API result and the post-evaluation flows.
- A conformance suite runs every case against both engines and compares the result graphs. The rule engine is
  "ready" only when it passes.
- Explicit `false` everywhere a truth value exists (TR292A: booleans default to `false`).
- The rule engine implements ontology semantics, including `icm:chooseFrom`, with rules; Java builtins only for
  what rules cannot express.

Non-goals
- Replacing or deleting the Java engine.
- Provider-aware S-tag selection (`bbf921j-3or`), event-based report triggers (TR290 §3.4), report
  destinations, extension models (validity, guarantee, utility, preference, proposal), TR292B update state machine.
- Persisting the inferred model across evaluations. It is rebuilt each time.

Anti-goals (would make this a failure even if it technically works)
- The two engines agreeing only because the Java engine's quirks were copied into the rules.
- Semantics hidden in the handler or the report builder instead of the engines and the specification.
- Changing observable behaviour of the API or the demos in the first phase.

## 3. Constraints

- Java 21, Spring Boot 3.3, Apache Jena 4.10 (`GenericRuleReasoner`, ARQ, TDB2). No new runtime dependencies expected.
- Precedence when sources disagree: the TM Forum PDFs in `docs/spec/` (v3.6.0 for TR290-TR292, TR290 v3.8.0 for
  reports), then the ontology TTL files, then the supplied rule files, then existing Java behaviour. The 3.6.0
  namespaces are kept (`http://tio.models.tmforum.org/tio/v3.6.0/...`).
- The ontology TTL files are gitignored (proprietary). Anything that needs them at build or test time depends on
  how they are supplied (open question 1).
- `docs/12 - load_dlog.md` explains why the supplied rules look as they do: they were translated from RDFox
  Datalog. Its translation table maps `<` and `>` to `lt`/`gt`, which are not Jena builtins (fixed since). The
  dlog `FORALL` and set-difference rules were approximated or skipped; these need a proper stratified design.
- Jena rules run over one merged model per evaluation (doc 12); the handler already assembles expression,
  observations and resources into one ephemeral model.
- The existing 163 unit tests and 27 integration tests define current behaviour; `NegotiationIT.degradedToActive_*`
  already fails for an unrelated reason (`bbf921j-dp2`).
- **Baseline (builder's call, decided here):** the work currently uncommitted in the working tree (the TR290
  reports, units, spec-form seed, `JenaBuiltins`, `Quantities`, rule fixes, the Access demo documentation) is the
  starting point and is committed before phase 1 begins. The gate "all existing tests pass unchanged" means: the
  unit suite fully green and the integration suite green except `NegotiationIT.degradedToActive_*`, which is
  excluded by name until `bbf921j-dp2` is fixed.

## 4. Decisions already made by the owner

1. Startup option selects the engine; default Java; the Jena engine reports "not ready" until it passes the tests.
2. The Java engine populates the graph with its results, acting as a rules engine producing the same output.
3. Explicit `false` is written.
4. The shared output matches what the PDFs define.
5. The handler turns results into TR290 reports.
6. The Java evaluator stays alongside the rules so both can be compared.
7. `icm:chooseFrom` is implemented as a Jena rule where possible; claiming resources (allocation write-back)
   remains a Java handler action.
8. The seed intent and the observation API use the specification's forms (done: lists, explicit
   `set:intersection`, `log:allOf` on the intent, `met:metric`, quantities with `quan:unit`).

## 5. Design

### 5.1 The engine seam

```
EvaluationEngine
  String name()                                  // "java" | "jena"
  EngineStatus status()                          // ready? reason if not
  EvaluationOutcome evaluate(EvaluationInput in)

EvaluationInput:  intentId, expression (Turtle), observations (Model), resources (Model),
                  held resources (set of IRIs the intent already holds)
EvaluationOutcome: the result graph (a Jena Model in the vocabulary of 5.2)
```

- Two implementations: `JavaEvaluationEngine` (wraps `TurtleEvaluator`) and `JenaEvaluationEngine`.
- Selection by property at startup (`@ConditionalOnProperty` or a small factory). Mode `compare` (a third value)
  runs both, logs any difference in the result graphs, and returns the Java outcome. It is for development and CI.
- Readiness: `JenaEvaluationEngine.status()` reports not ready until the conformance suite is green (5.6).
  If `jena` is selected while not ready, the application starts, `/health` reports the evaluator as not ready
  with the reason (builder's call whether this is HTTP 503), and evaluations are skipped with an error log rather
  than producing wrong results.
- `EvaluationDispatcher` depends only on `EvaluationEngine`.

### 5.2 The result graph (the shared contract)

Stored in a per-intent named graph `http://tmforum.org/api/v5/intents/{id}/inferred`, replaced on every
evaluation, so Fuseki can show it. It contains **only** the triples below; engine-internal helper triples are
filtered out. Elements are identified by IRI; blank-node elements cannot be reported on (TR290 §4.2) and are not
in the contract.

| Triple | For | Source |
|---|---|---|
| `?e rdf:value "true"^^xsd:boolean` or `"false"^^xsd:boolean`, exactly one | every IRI-identified `icm:Intent`, `icm:Expectation` (all subclasses) and `icm:Condition`/`log:Condition` in the intent | TR292A §4: associated value of boolean type, default `false`, contributions combined by conjunction (`log:allOf`). TR290 §2.2: intent and expectation truth values are compliance. |
| `?target rdfs:member ?resource` | each resource chosen for an `icm:DeliveryExpectation`, including resources the intent already holds | TR290 §2.5.3: choices become members of the target. |
| `?e icm:reason ?r . ?r a icm:Reason ; rdfs:label "..."` | an element that is `false` because a function errored, nothing could be chosen, or a comparison failed: no observation, unit mismatch, no candidate of the delivery type, a value outside its bound (text `<observed> <unit> <op> <bound> <unit> is false`) | TR290 §4.5.2 (`icm:Reason`); TR292C §8 errors. The PDFs define no engine-level error vocabulary beyond this; the text is the human explanation. |
| `?condition met:observed ?observation`, plus the observation individual's own triples (`a met:Observation`, `met:observedMetric`, `rdf:value`, `quan:unit`) | a condition whose first argument is a metric: the observation individual it used | TR290 §4.6 / TR292G. Lets the report show the observed value and unit without the report builder re-deriving them or reading the observations store. |

Not in the contract:
- `icm:result`. TR290 §4 puts it in *reports* (`icm:IntentReport`, `icm:ExpectationReport`,
  `icm:ConditionReport`), which the handler builds. The supplied rule files put `icm:result` on intent elements;
  the rewritten rules must not (see 5.5).
- The handling state. `imo:StateCompliant`/`StateDegraded` follow from the intent's value (TR292B); the handler
  derives it and raises `imo:Complies`/`imo:Degrades` on change.
- Values of anonymous function nodes (intermediate results).

Input conventions both engines read: a neutral fact for resources already held by the intent,
`<urn:tmf921:evaluation> <urn:tmf921:evaluation#holds> ?resource`, added by the handler. Rules therefore need no
domain vocabulary (`pon:`) to express "held".

### 5.3 What the handler does with the graph

- **`ResultAdapter`** builds today's `EvaluationResult` (state, reason string, flat condition list) from the
  graph, so Flows 1-4, `HandlerStateWriter`, `BestEffortLimits` and notifications keep working unchanged. Intent
  value `true` maps to `Fulfilled`, `false` to `Degraded`.
- **`ReportBuilder`** reads the result graph **and the expression** instead of the in-memory `ElementResult` tree,
  and produces the TR290 report as it does now (`icm:about`, `icm:result`, `icm:resultFrom`, `icm:reason`, target
  reports, observations only for `icm:ObservationReportingExpectation`). `ElementResult` and `ReportTree` are then
  removed. Building the report is the handler's job (TR290 §4), so reading report-related parts of the
  expression is legitimate there; that is metadata about *what to report*, not evaluation semantics:
  the reporting scope (members of the reporting expectation's target), the report class, the structure
  (`icm:resultFrom` follows each element's `log:allOf` arguments) and each condition's bound and operator.
  What it never does is decide whether an element is true.
- **Blank-node elements.** Today's behaviour is unchanged: `TurtleEvaluator` already reports only IRI-identified
  elements (TR290 §4.2), so blank-node conditions have never appeared in reports. They contribute to their parent's
  value but have no `rdf:value` entry in the contract and no report of their own.
- **Phase 1 keeps the flat condition list.** For the Java engine the adapter continues to use the evaluator's
  existing flat condition list (including `observed`/`bound`/`operator` for `BestEffortLimits`) alongside the graph,
  so Flow 3 does not change. The adapter derives those details from the graph plus the expression only for the
  rule engine, in phase 3.
- Allocation write-back (Flow 4) is unchanged: it claims the chosen exclusive resources, skipping held ones.
- Evaluations of one intent stay serialised.

### 5.4 Java engine

`TurtleEvaluator` already builds the model the rules would work on. Changes:
- After evaluating, write the contract triples (5.2): `rdf:value` on every reportable element including explicit
  `false`, chosen target members, reasons, observation links.
- `JavaEvaluationEngine` returns the contract-filtered model; the existing flat condition list stays available to
  the adapter until the adapter reads only the graph.
- **Deviation policy (builder's call, decided here).** Where the Java engine differs from the PDFs, phases 1-2 do
  not change the Java behaviour (that would change the demos). Each difference is recorded in a deviation register
  (a file next to the conformance corpus) with the PDF reference, what Java does and what the specification says.
  The affected corpus case carries one expectation per engine: Java's current output and, for the rule engine, the
  specification's. A deviation is only ever resolved by an explicit decision, never silently. Known so far:
  1. several set functions on one node are *intersected* by Java for legacy expressions, whereas TR292A says
     *union*. The seed no longer relies on it: its pools use an explicit `set:intersection`, which both engines
     must implement. The rule engine implements only the PDF (union) behaviour for the legacy form;
  2. a container used as an argument of `set:resourcesWithPropertyObject` is expanded to its members. The seed
     relies on this for "C-tags of the selected S-VLAN" (the PDFs have no way to say it). See open question 2.

### 5.5 Jena rule engine

Rules are evaluated in stages over a model that contains the ontology TTL files, the expression, the observations
and the resources. Stages are chained (`ModelFactory.createInfModel(stageB, stageA)`) so negation runs over
saturated facts.

- **Stage 0, input shape.** The rules read functions in their specification form: a property whose object is an
  `rdf:List` of arguments, `?s fn ( a b )` (TR292C §2). No Java rewriting step. The supplied rule files use a
  different shape (typed nodes with `rdf:first`/`rdf:rest`) inherited from RDFox; they are rewritten, not patched.
- **Stage A, positive facts.** Function results as `rdf:value`: quantity comparison (`quan:*`, unit-aware via a
  builtin using `Quantities`), set functions (`set:*`, lists of classes, subclasses via the ontology, explicit
  `set:intersection/union/difference`), `met:lastValue`/`met:lastObserved` (newest `met:obtainedAt`, a builtin),
  `log:match*`.
- **Stage B, defaults, combination, negation, choice.** Boolean default `false`; conjunction of contributors
  (TR292A); `log:allOf`/`anyOf`/`noneOf`/`oneOf` over lists (TR292E) using list-walking helper rules (propagate each
  list cell to its function, read `rdf:first`), `noValue` over stage A facts; `icm:chooseFrom` (done in prototype:
  `tio_choose.rules`) and held resources; roll-up to expectations and the intent; reasons.
- **Builtins** (`JenaBuiltins`): `lexLess` (exists), a unit-aware quantity comparison, newest-observation selection.
  Only where rules cannot express it.
- **List handling.** Fixed arity: `listEntry`/`listLength`. Variable arity: cell propagation helpers.
- **Quantity arguments are classified inline in stage A** (`tio_a_quantity.rules`, see its header). Each argument
  cell resolves to `tmf:argNode`/`tmf:argValue` (+ `tmf:argUnit`) or to `tmf:argMissing`. The cases are a literal,
  a node with its own `rdf:value`, `[ met:lastValue ( m ) ]` (newest observation of `m`), and any other node, which
  is treated as a metric (closed world: no observation means missing). Nested function-valued arguments therefore
  *do* need a special case. Any other value-producing function used as a quantity argument (for example
  `[ quan:sum ( ... ) ]`) needs its own classification rule. Without one it is taken as a metric, and it must not
  get `rdf:value` derived in stage A, because the classification negates `rdf:value` on input argument nodes.
- `rdf:value` is the associated-value property (TR292C §2-4 and every example). TR292C §11.7 names
  `tio:assumedValue` as the default `fun:resultProperty`; it appears nowhere else in the specification set and
  is not defined in the ontology, so it is treated as an editorial inconsistency.
- One source of truth for the rule files. Today `tio_all.rules` exists in `ontology/jena-rules/` and
  `src/main/resources/rules/` (identical copies). The runtime copy is authoritative; the ontology copy is dropped
  or generated.

#### 5.5.1 Decision from the stage-chaining spike (Task 10)

Proved by `RuleStagesSpikeTest` (all four operators, all 8 true/false combinations, empty lists, non-boolean
members, defaulted members, nesting up to 4 levels; 6-100 fixed-seed shuffles of triples and rule order; both
chainings agree).

- **Chaining.** `createInfModel(stageB, stageAInf)` is correct *for rules that obey the invariant below*;
  copying stage A into a plain `Model` first gives the same results, with no measurable difference in cost
  (about 1.2 ms for a 20-condition intent, rules parsed each time). Either is fine; Tasks 12-16 use chaining.
- **Invariant: `noValue` only over facts that are complete when the stage starts** (input structure or a
  previous, saturated stage), never over facts the same stage derives. Changing the chaining does not rescue
  rules that break it: the naive `allOf` ("true if no member is not true", default false via
  `noValue(?c rdf:value)`) gives wrong or double (`true` and `false`) values in 80-100% of runs, in both chainings.
- **Pattern: two stages, logic is monotone.**
  - Stage A also derives the structural fact `?c tmf:hasContributor ?p` for every `?c ?p ?args` with
    `?p a fun:Function`.
  - Stage B default: `(?c a log:Condition) noValue(?c tmf:hasContributor) noValue(?c rdf:value) -> false`. Safe
    because nothing in stage B gives a value to an element without a contributor. Preconditions: (a) the function
    ontology TTLs (LogicalOperators etc.) are in stage A's input, so `hasContributor` is derived for operator
    conditions (the spike inlines them as `ONTOLOGY`); (b) every stage-B rule that writes `rdf:value` has a
    subject with a stage-A-derived `hasContributor`, including the expectation and intent truth values of Tasks
    14-16; (c) `rdf:type log:Condition` (and any type the default reads) is never derived in stage B: type
    inference belongs in stage A.
  - Stage B list cells: `(?c log:allOf ?l) -> (?l a tmf:LogCell)` (one rule per operator),
    `(?l a tmf:LogCell) (?l rdf:rest ?r) -> (?r a tmf:LogCell)`. Each cell's member is classified `tmf:arg`
    `T`/`F`/`Skip` (`Skip`: non-boolean value or literal (`notDType`), or no value, no contributor and untyped).
  - Each cell carries a summary computed from the tail: `rdf:nil` has `tmf:trues N0`, `tmf:hasFalse No`; a cell's
    summary is derived from its own `arg` and its `rdf:rest`'s summary (`trues` capped at `N2`). The summary exists
    only when the whole suffix is resolved, so a nested operator's value is simply waited for.
  - Operators read the head cell: allOf true iff `hasFalse No` and `trues` not `N0`; anyOf true iff `trues` not
    `N0`; noneOf true iff `N0`; oneOf true iff `N1`; each is false in the complementary cases.
- **Empty lists (TR292E §6):** allOf, anyOf, oneOf `( )` = false; noneOf `( )` = true. (allOf empty is *false*
  in the PDF, not the vacuous true.)
- **For Tasks 12-16:** follow the invariant; derive every structural fact a negation needs in stage A; have
  every stage A function produce an explicit `true`/`false` (an error is `false` plus `icm:reason`), since an
  element whose contributor never resolves leaves the walk waiting and stays without value (a final
  post-B fill to `false` would not propagate into enclosing operators). Not covered by the spike: conjunction of
  *several* contributors on one element (TR292A §4; TR292E §4 p.13, which also gives the default `false`).
  "No contribution that is not true" is the naive pattern again; make it monotone (false if any contribution is
  false; true once a stage-A-known number or list of contributions are all true, e.g. a count builtin in stage A).
  Never write per-contributor results directly onto the element's `rdf:value`: a member with conflicting values
  gets two classifications and its operator two values. No Java builtin was needed for the operators.
- **Deviations from the Java engine** (`TurtleEvaluator`, the conformance oracle). The spike follows the PDFs:
  - `log:allOf ( )`: Java true (`applyCombinator`, `allMatch` on an empty list, :1333-1338); TR292E §6.1 false.
    Empty anyOf/noneOf/oneOf agree with the PDF.
  - A bare typed `log:Condition` with no properties: Java true ("Unknown/opaque — pass silently", :866-868);
    TR292E §4 (p.13) false by default.
  - Literal boolean members (`( true false )`): Java drops them (`filter(RDFNode::isResource)`, :815); TR292E §2
    makes them boolean arguments, so the spike counts them.
  - An untyped member with no value: Java counts it true (opaque, :866-868); the spike drops it as non-boolean
    (TR292E §2).
  - A list whose children are all opaque: Java fails the operator (`opaqueChildren`, :821-825), so noneOf is false;
    the spike drops those members and noneOf of the empty remainder is true (TR292E §2, §6.10).

  Task 13 MUST add corpus cases for each of these (`logic-*`, each with `expected.ttl` = the Java engine's output
  and `expected.jena.ttl` = the PDF result where they differ) and the matching `DEVIATIONS.md` entries, so the
  Jena column shows no unexplained mismatches. (Not added now: the register test requires an existing case.)

### 5.6 Conformance and comparison

- A **conformance corpus**: cases of (expression, observations, resources, held resources) with the expected
  contract triples. It starts from the existing tests (the `TurtleEvaluatorTest` snippets, `HsiSeedIntentTest`,
  `ReportBuilderTest`, `AccessDemoIT` scenarios, the demo scenarios in `Access_HSI_Demo.md`) and grows as the rules
  grow.
- One parameterised test runs every case against every registered engine and compares the contract graph
  (isomorphism after filtering). Java runs first and fixes the Java engine's output; Jena must match, or the
  difference must be recorded as a deliberate, specification-based deviation with the PDF reference.
- `compare` mode (5.1) applies the same comparison to live traffic.
- Readiness of the rule engine is a declared status in code that is switched only in the change where the whole
  corpus passes, guarded by a test that asserts the declared status agrees with the suite.

## 6. Phasing (for the implementation plan)

0. **Baseline.** Commit the pending working-tree changes (see section 3). The demos (`Access_HSI_Demo.md`) and
   every existing test run on the `java` engine throughout; nothing requires `jena` until it is ready (phase 4).
1. **Seam and contract, no behaviour change.** `EvaluationEngine`, `JavaEvaluationEngine`, contract writer in the
   Java evaluator, `inferred` graph, `ResultAdapter`, `ReportBuilder` reading the graph; `intent.evaluator`
   property with `java` the only ready engine; health reporting. All existing tests pass unchanged.
2. **Conformance suite and compare mode.** Corpus from existing tests; parameterised harness; Java engine passes.
   `compare` mode is exercised by the conformance suite and, as an optional CI job, by the integration tests; it
   is not required for every test.
3. **Rule engine, stage by stage**, each stage adding corpus cases that pass for both engines:
   a. stage 0/A conditions: quantity functions with units, `met:lastValue`, list arguments;
   b. stage B logic: defaults, conjunction, `allOf/anyOf/noneOf/oneOf`. **Starts with a time-boxed spike** to prove
      that negation (`noValue`) over facts derived in the earlier stage is correct when stages are chained with
      `createInfModel(stageB, stageA)`. A prototype of this already exists and passes
      (`JenaRulesTest`, `tio_choose.rules`: `noValue` over a stage A helper fact), but `allOf` needs negation over
      *derived function results* and is the real test. If chaining proves unreliable, the fallback is to run each
      stage to saturation as a separate model and copy the result into the next, then, failing that, a Java builtin;
      the decision is made at the end of the spike;
   c. sets and pools (list forms, intersection/union/difference, subclasses, pools that depend on a choice);
   d. `chooseFrom`, held resources, delivery expectations and roll-up to the intent;
   e. reasons, observation links, error cases.
4. **Switch on.** Rule engine status becomes ready; documentation (README, CLAUDE.md) describes the real design;
   decide the fate of the unused reasoner bean, the duplicate rule files and `HandlerStateWriter`'s
   `imo:conditionPassed` graph.

## 7. Left to the builder

- Package and class names, the exact selection mechanism, the HTTP status of a not-ready `/health`.
- How the conformance corpus is stored (Turtle files per case or in code).
- How reasons are worded (the contract fixes the structure, not the text), as long as engines produce the same
  text for the same case.
- Whether to keep `HandlerStateWriter`'s `handlerState` graph or retire it in favour of the inferred graph.
- Performance tuning of the ontology-loaded model (measure first: about 4,200 triples of ontology per evaluation).

## 8. Known inconsistencies and risks

- The ontology defines `imo:associatedValueType`/`imo:associatedValueCombination` but attaches them to no class;
  the specification text (TR292A) describes the defaults and uses the prefix `tio:`. The rules encode the defaults
  for `icm:Condition`, expectations and the intent.
- TR292A's union default for several set functions on one node versus the Java and seed intersection (5.4).
- Negation in forward rules is order-sensitive; the stage split is how this design avoids it, and the first rule
  stage to be built should prove it.
- `BestEffortLimits` (Flow 3) reads per-condition `observed`/`bound`/`operator` details from the flat condition
  list. The adapter must produce them from the graph plus the expression for the rule engine.
- Unit parsing covers the SI and binary prefixes and a small registry of base symbols (`Mbps`, `ms`, `bit`, `B`,
  `percent`...). Composite units (`m/s`) are not decomposed; an unknown unit matches only itself.
- Rules rerun on every evaluation; very large expressions may need selective rule application (doc 12 §5).

## 9. Decisions on the questions raised during review (owner, 2026-10-18)

1. **Ontology supply and test scope.** The ontology files are licensed and cannot be distributed, so the tests
   that need them are intended to run **only on the owner's machine**; there is no CI that runs them. Tests that
   need the ontology still skip themselves with a clear message when the files are absent, so a fresh checkout
   builds and runs everything else. The Docker image keeps copying `ontology/` from a local build context. The
   readiness guard (5.6) is enforced wherever the files are present, which in practice is the owner's machine.
2. **The container-argument extension is kept.** Both engines expand a container argument of
   `set:resourcesWithPropertyObject` to its members, as a documented project extension recorded in the deviation
   register, and the rule engine implements it.

Status: approved by the owner on 2026-10-18. Next step: the implementation plan.

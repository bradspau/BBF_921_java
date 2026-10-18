# Selectable Evaluation Engines Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the application run either the existing Java evaluator or a Jena-rule evaluator (`intent.evaluator=java|jena|compare`), both producing the same result graph, with the handler turning that graph into TR290 reports; the rule engine becomes ready only when it passes the same conformance suite as Java.

**Architecture:** An `EvaluationEngine` seam in a new `org.tmforum.intent.engine` package. Both engines return a *result graph* (explicit `rdf:value true|false` on each intent element, chosen target members, `icm:reason`, linked observations). The handler derives the API result, the handling state and the TR290 report from that graph plus the expression. A parameterised conformance suite runs a corpus of cases against every engine. The rule engine is built stage by stage (functions, then logic/defaults/choice) against that suite.

**Tech Stack:** Java 21, Spring Boot 3.3, Apache Jena 4.10 (`GenericRuleReasoner`, ARQ, TDB2), JUnit 5, Maven (`mvn`, not `./mvnw`).

**Spec:** `docs/superpowers/specs/2026-10-18-evaluation-engines-design.md` (read it first; this plan implements it). Beads issue `bbf921j-4qm`.

## Global Constraints

- Java 21, Spring Boot 3.3.5, Apache Jena 4.10.0. No new runtime dependencies.
- Namespaces stay at TIO v3.6.0: `http://tio.models.tmforum.org/tio/v3.6.0/{IntentCommonModel,IntentManagementOntology,MetricsAndObservations,QuantityOntology,LogicalOperators,SetOperators}/`.
- Precedence when sources disagree: TM Forum PDFs in `docs/spec/` > ontology TTL files > supplied rule files > existing Java behaviour.
- `intent.evaluator` default is `java`; the Jena engine reports not ready until the whole conformance corpus passes on it.
- The result graph holds `rdf:value "true"|"false"^^xsd:boolean` (explicit false) on IRI-identified `icm:Intent`, `icm:Expectation` (all subclasses) and `icm:Condition`/`log:Condition`; `rdfs:member` for chosen delivery resources; `icm:reason`/`icm:Reason`; `met:observed` plus the observation's own triples. It never contains `icm:result` (that is only in handler-built TR290 reports).
- Result graph lives in `http://tmforum.org/api/v5/intents/{id}/inferred`, replaced on every evaluation.
- Held resources reach engines as `<urn:tmf921:evaluation> <urn:tmf921:evaluation#holds> ?resource`.
- Tests that need the licensed ontology files (`ontology/*.ttl`) run only on the owner's machine and skip themselves (JUnit `Assumptions.assumeTrue`) with a clear message when absent. No CI.
- Baseline gate for "no behaviour change": unit suite fully green; integration suite green except `NegotiationIT.degradedToActive_flowTwo_afterFulfilledReEvaluation` (`bbf921j-dp2`).
- Commit after each task. Commit messages end with `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.

## Review Focus

1. An expression with no `icm:Intent` at all (many unit tests are metric-only): the engine must not fail; the result graph is empty and no TR290 report is created (today's behaviour).
2. A condition on an IRI with no observation and no reporting expectation: the graph still holds explicit `false` and an `icm:reason`.
3. Re-evaluating an intent that already holds its resources: the result graph is identical to the previous evaluation (held resources are kept, nothing re-chosen).
4. Large inventories: only chosen resources appear in the graph, never whole pools (a pool of thousands of UNIs must not be copied).
5. Bad configuration: unknown `intent.evaluator` fails startup with a clear message; `jena` selected while not ready, or ontology files missing, starts the application, reports not ready on `/health` and skips evaluations without an exception.

---

## File Structure

New package `src/main/java/org/tmforum/intent/engine/`:
- `EvaluationEngine`, `EvaluationInput`, `EvaluationOutcome`, `EngineStatus`: the seam.
- `JavaEvaluationEngine`, `JenaEvaluationEngine`, `ComparingEngine`, `EngineConfig`: implementations and selection.
- `ResultContract`: vocabulary constants and `filter(Model)`; `ResultGraphWriter`: Java tree to graph; `InferredGraphStore`: persistence; `ResultAdapter`: graph to `EvaluationResult`; `ContractExtractor`: reasoner output to contract.
- `OntologyModels`: loads and caches the ontology TTL files.

Modified: `TurtleEvaluator`, `EvaluationDispatcher`, `ReportBuilder`, `HealthController`, `GraphNodes`, `IntentService`, `application.yml`, `JenaBuiltins`, rule files under `src/main/resources/rules/`.
Test helpers/resources: `src/test/java/org/tmforum/intent/unit/TestInputs.java`, `src/test/java/org/tmforum/intent/conformance/`, `src/test/resources/conformance/<case>/`.

---

# Phase 0: Baseline

### Task 0: Commit the pending work

- [ ] **Step 1:** `mvn test` expects `Tests run: 163, Failures: 0`; `mvn test -Dtest='*IT' -DfailIfNoTests=false` expects only the `bbf921j-dp2` error.
- [ ] **Step 2:** Commit the working tree (code, tests, docs, rules, design doc, this plan) in logical commits; leave out `.beads/interactions.jsonl`. Push.

---

# Phase 1: Seam and contract (no behaviour change)

### Task 1: Engine seam, selection and health

**Files:**
- Create: `engine/EvaluationEngine.java`, `EvaluationInput.java`, `EvaluationOutcome.java`, `EngineStatus.java`, `JavaEvaluationEngine.java`, `JenaEvaluationEngine.java`, `EngineConfig.java`
- Modify: `api/HealthController.java`, `src/main/resources/application.yml` (add `intent.evaluator: java`)
- Test: `src/test/java/org/tmforum/intent/unit/EngineSelectionTest.java`

**Interfaces:**
- Produces:
  - `interface EvaluationEngine { String name(); EngineStatus status(); EvaluationOutcome evaluate(EvaluationInput in); }`
  - `record EngineStatus(boolean ready, String reason)` with `static EngineStatus ready()` and `static EngineStatus notReady(String reason)`.
  - `record EvaluationInput(String intentId, String expression, String observations, String resources, Collection<String> held)` (Turtle strings, as the dispatcher has them today).
  - `record EvaluationOutcome(Model resultGraph, EvaluationResult result)`.
  - `EngineConfig` exposes `@Bean @Primary EvaluationEngine activeEngine(JavaEvaluationEngine, JenaEvaluationEngine, @Value("${intent.evaluator:java}") String mode)`.

- [ ] **Step 1: Write `EngineSelectionTest`** (plain unit test constructing `EngineConfig` directly): `java` returns an engine named `java` and ready; `jena` returns `jena` with `status().ready()==false` and a non-blank reason; `compare` returns an engine named `compare`; `bogus` throws `IllegalArgumentException` whose message contains `intent.evaluator must be java, jena or compare`.
- [ ] **Step 2:** Run `mvn test -Dtest=EngineSelectionTest`; expect compile failure/FAIL.
- [ ] **Step 3: Implement** the types above. `JavaEvaluationEngine.evaluate` calls `TurtleEvaluator.evaluate(expression, observations, resources, held)` and returns `new EvaluationOutcome(ModelFactory.createDefaultModel(), result)` (graph filled in Task 3). `JenaEvaluationEngine.status()` returns `notReady("rule engine not implemented")`; its `evaluate` throws `IllegalStateException`. `compare` is a stub returning the Java engine wrapped with name `compare` until Task 9.
- [ ] **Step 4: Health:** `GET /health` adds `"evaluator": {"engine": <name>, "ready": <bool>, "reason": <string or omitted>}`; overall `status` is `DEGRADED` with HTTP 503 when the dataset is down **or** the configured engine is not ready.
- [ ] **Step 5:** Extend `IntentControllerTest`-style slice or add `HealthControllerTest` asserting the JSON contains `evaluator.engine == "java"` and `ready == true`, and 503 with `ready == false` for a not-ready engine stub.
- [ ] **Step 6:** `mvn test` all green. Commit `feat: evaluation engine seam and intent.evaluator selection`.

### Task 2: Dispatcher runs through the engine

**Files:** Modify `handler/EvaluationDispatcher.java`; Test: existing suites.

**Interfaces:** Consumes `EvaluationEngine`, `EvaluationInput`, `EvaluationOutcome` (Task 1). Produces nothing new.

- [ ] **Step 1:** In `EvaluationDispatcher`, replace the `TurtleEvaluator` dependency with `EvaluationEngine`. Build `EvaluationInput(intentId, expressionTurtle, obsTurtle, resTurtle, heldResources)`; use `outcome.result()` wherever `result` is used today. Before evaluating: `if (!engine.status().ready()) { log.error("evaluator '{}' is not ready: {}", ...); return; }`.
- [ ] **Step 2:** `mvn test` and the integration suite: baseline gate holds (identical results). Add `EvaluationDispatcherEngineTest`: a stub engine that is not ready causes no report to be created for an ACTIVE intent.
- [ ] **Step 3:** Commit `refactor: dispatcher evaluates through EvaluationEngine`.

### Task 3: Result contract and the Java result-graph writer

**Files:**
- Create: `engine/ResultContract.java`, `engine/ResultGraphWriter.java`, `src/test/java/org/tmforum/intent/unit/TestInputs.java`
- Modify: `handler/TurtleEvaluator.java` (record the observation individual used by each metric condition: add key `"observation"` (IRI string) to the quantity condition detail map, keep existing keys), `engine/JavaEvaluationEngine.java`
- Test: `unit/ResultGraphWriterTest.java`

**Interfaces:**
- Produces:
  - `ResultContract`: `Model filter(Model m)` keeps only contract triples (see Global Constraints); `boolean sameResults(Model a, Model b)` (isomorphism of filtered models); constants `Property ICM_REASON`, `Property MET_OBSERVED`, `Resource ICM_REASON_CLASS`.
  - `ResultGraphWriter.write(ReportTree tree, Model observations): Model`: builds the contract graph from the evaluator's `ElementResult` tree (kinds, `passed`, leaf `details` incl. `selected`, `error`, `observation`, `metric`, `observed`, `observedUnit`); `observations` is the parsed observations model, used to copy the linked observation individuals.
  - `TestInputs`: `static String seedIntent()`, `static String inventory()`, `static String observations(String... metricValueUnit)`, `static final String[] ALL_OK`, `static final String[] BANDWIDTH_ONLY` (move these from `HsiSeedIntentTest`/`ReportBuilderTest`, which then use `TestInputs`).

- [ ] **Step 1: Write `ResultGraphWriterTest`** against the real seed + inventory (`TestInputs`), via `TurtleEvaluator` and `ResultGraphWriter`:
  - `fulfilled_everyElementHasExactlyOneBooleanValue`: for each of `HSIIntent`, the 5 expectations and the 5 conditions, `SELECT (COUNT(?v)) ... rdf:value ?v` equals 1 and all are `true`.
  - `partial_hasExplicitFalseAndReasons`: with `BANDWIDTH_ONLY`, latency/jitter/packet-loss conditions have `rdf:value false` and an `icm:reason` whose `rdfs:label` is `no observation: LatencyMetric` (etc.); the intent is `false`.
  - `deliveryChoicesAreMembersOfTheirTargets`: `bbf:SelectedUNIInterface rdfs:member pon:UNI-001-1`, `SelectedSVLANAllocation rdfs:member pon:SVLAN-N-HSI`, `SelectedCTAGAllocation rdfs:member pon:CTAG-N-001`, and no other `rdfs:member` triples.
  - `observationsAreLinkedAndCopied`: `bbf:HSI_LatencyCondition met:observed ?o . ?o a met:Observation ; met:observedMetric bbf:LatencyMetric ; rdf:value 8 ; quan:unit "ms"`.
  - `noIcmResultAndNoHelperTriples`: `ASK { ?s icm:result ?o }` is false; the graph equals `ResultContract.filter(graph)`.
  - `unitMismatchGivesFalseWithReason`: latency posted without unit gives `false` and a label containing `unit mismatch`.
  - Review Focus 1: a metric-only expression (no `icm:Intent`) yields an empty graph, no exception.
  - Review Focus 2: the seed with its reporting expectation removed (`replaceAll` of the `bbf:HSIMonitoringExpectation` block, as `ReportBuilderTest` does) still yields explicit `false` plus a reason for an unobserved condition.
  - Review Focus 4: an inventory with 1,000 free UNIs yields exactly one `rdfs:member` triple for `bbf:SelectedUNIInterface` and no pool members in the graph.
- [ ] **Step 2:** Run `mvn test -Dtest=ResultGraphWriterTest`; expect FAIL (classes missing).
- [ ] **Step 3: Implement** `ResultContract`, `ResultGraphWriter` (reuse the reason wording in `ReportBuilder.reason`: move that text builder to a shared static `Reasons.of(Map<String,Object> detail)` used by both), and the `observation` detail key in `TurtleEvaluator.buildObsIndex`/`evalTwoArg`. `JavaEvaluationEngine.evaluate` now returns the written graph.
- [ ] **Step 4:** `mvn test`: all green (the new detail key is additive). Commit `feat: Java engine writes the shared result graph`.

### Task 4: Persist the inferred graph

**Files:** Modify `graph/GraphNodes.java` (add `inferredGraphUri(String intentId)` returning `http://tmforum.org/api/v5/intents/{id}/inferred`), `service/IntentService.java` (drop the graph in `delete`), `handler/EvaluationDispatcher.java`; Create `engine/InferredGraphStore.java`; Test: `unit/InferredGraphStoreTest.java`, extend `integration/TioReportIT.java`.

**Interfaces:** Produces `InferredGraphStore(Dataset)` with `void replace(String intentId, Model graph)` and `void drop(String intentId)`.

- [ ] **Step 1: Tests:** `replace_thenReplaceAgain_leavesOnlyTheLatest` (in-memory `DatasetFactory.createTxnMem()`); `drop_removesTheGraph`; `GraphNodesTest` asserts the URI format. In `TioReportIT` add `inferredGraphHoldsTheLatestResultsInFuseki`-style check using the injected `Dataset`: after Fulfilled, `GRAPH <.../inferred>` has `bbf:HSIIntent rdf:value true`.
- [ ] **Step 2:** Run them; expect FAIL.
- [ ] **Step 3: Implement.** The dispatcher calls `inferredGraphStore.replace(intentId, outcome.resultGraph())` inside the per-intent evaluation lock, before the report is created. `IntentService.delete` calls `drop` when the intent was deleted.
- [ ] **Step 4:** `mvn test` and the integration suite (baseline gate). Commit `feat: store the result graph per intent`.

### Task 5: Report builder reads the graph and the expression

**Files:** Modify `handler/ReportBuilder.java`, `handler/EvaluationDispatcher.java`; Test: `unit/ReportBuilderTest.java`.

**Interfaces:**
- Consumes: `ResultContract`, result graph (Task 3).
- Produces: `Model ReportBuilder.build(Model expression, Model resultGraph, ReportContext ctx)` returning `null` when the expression has no IRI-identified `icm:Intent`. Reads from the **expression**: the reporting scope and `ObservationReportingExpectation` flag, each element's kind and structure (children = IRI elements reachable through its `log:allOf`/`anyOf`/`noneOf`/`oneOf` lists, passing through blank nodes), `icm:target` and bound/operator for conditions. Reads from the **graph**: every `icm:result` value (`rdf:value`), chosen members, reasons, observations. It never decides truth.

- [ ] **Step 1: Make `ReportBuilderTest` parameterised over two paths** (`TREE` = existing `build(ReportTree, ctx)`, `GRAPH` = the new method fed by `ResultGraphWriter` output): every existing test in the class must pass for both. Add `blankNodeConditionIsNotReportedButAffectsItsParent`, and `observationReportingIncludesUnits`.
- [ ] **Step 2:** Run `mvn test -Dtest=ReportBuilderTest`; expect FAIL for `GRAPH`.
- [ ] **Step 3: Implement** the graph-based `build`; share report-node construction with the tree path to avoid divergence.
- [ ] **Step 4:** Both paths green. Commit `feat: build TR290 reports from the result graph`.

### Task 6: Dispatcher builds reports from the graph; remove the tree

**Files:** Modify `handler/EvaluationDispatcher.java`, `handler/ReportBuilder.java` (delete `build(ReportTree, ReportContext)`), `unit/ReportBuilderTest.java` (drop the `TREE` path). `model/ElementResult` and `model/ReportTree` stay: they are now the Java engine's private trace (read only by `ResultGraphWriter`), and a class comment says so.

**Interfaces:** Consumes `ReportBuilder.build(Model, Model, ReportContext)`; the dispatcher passes the parsed expression and `outcome.resultGraph()`; it no longer reads `result.report()`.

- [ ] **Step 1:** Switch the dispatcher call; run `mvn test` + integration suite; baseline gate holds and `TioReportIT` still asserts 11 report elements and gap-free report numbers.
- [ ] **Step 2:** Remove the tree path from `ReportBuilder` and its test; `mvn test` green.
- [ ] **Step 3:** Commit `refactor: reports come from the result graph only`.

---

# Phase 2: Conformance suite and compare mode

### Task 7: Conformance corpus and harness

**Files:** Create `src/test/java/org/tmforum/intent/conformance/ConformanceCase.java`, `ConformanceLoader.java`, `ConformanceTest.java`; `src/test/resources/conformance/<case>/` directories.

**Interfaces:**
- Produces: `record ConformanceCase(String name, String expression, String observations, String resources, Set<String> held, Model expected, Model expectedJena, boolean needsOntology)` and `ConformanceLoader.loadAll(): List<ConformanceCase>`.
- Case directory layout: `expression.ttl`, optional `observations.ttl`, `resources.ttl`, `held.txt` (one IRI per line), `expected.ttl` (contract), optional `expected.jena.ttl` (specification-based expectation where Java deviates), `case.properties` (`description`, `needsOntology=true|false`).

- [ ] **Step 1: Write `ConformanceTest`** (JUnit `@ParameterizedTest` over `engines × cases`): evaluate the case with the engine and assert `ResultContract.sameResults(outcome.resultGraph(), expectedFor(engine))`. Skip a Jena run when `status()` is not ready (report as skipped with the reason) and skip any case with `needsOntology=true` when `ontology/IntentCommonModel.ttl` is absent.
- [ ] **Step 2:** Add record mode: `-Dconformance.record=true` writes `expected.ttl` from the Java engine instead of asserting.
- [ ] **Step 3: Corpus (initial ≥ 15 cases):** `quantity-ok`, `quantity-violated`, `quantity-no-observation`, `quantity-unit-conversion` (0.15 Gbps vs 100 Mbps), `quantity-unit-mismatch`, `quantity-unitless`, `delivery-chooses-lowest`, `delivery-wrong-type`, `delivery-held-kept`, `pool-list-form`, `pool-explicit-intersection`, `pool-depends-on-choice` (container-argument extension), `seed-fulfilled`, `seed-partial`, `seed-no-free-uni`, `seed-reevaluation-held` (Review Focus 3: expected graph equals `seed-fulfilled`'s), `no-intent-metric-only` (Review Focus 1), `no-reporting-expectation` (Review Focus 2). Build expressions from the existing `TurtleEvaluatorTest`/`HsiSeedIntentTest` Turtle; record, then review each `expected.ttl` by hand against the PDFs.
- [ ] **Step 4:** `mvn test -Dtest=ConformanceTest` passes for `java`; commit `test: conformance corpus and engine-parameterised harness`.

### Task 8: Deviation register

**Files:** Create `src/test/resources/conformance/DEVIATIONS.md`; Modify `ConformanceLoader` to reject an `expected.jena.ttl` that has no matching entry.

- [ ] **Step 1: Test** `DeviationRegisterTest`: every case directory containing `expected.jena.ttl` is named in `DEVIATIONS.md`; every register entry names an existing case.
- [ ] **Step 2:** Run; expect FAIL; add the register with entries: (1) legacy multi-function set node (Java intersect, PDF union; case `pool-legacy-multifunction`, add it with both expectations), (2) container-argument extension (kept in both engines; no `expected.jena.ttl`, entry documents the decision).
- [ ] **Step 3:** Commit `test: deviation register`.

### Task 9: Compare mode

**Files:** Create `engine/ComparingEngine.java`; Modify `EngineConfig`; Test `unit/ComparingEngineTest.java`.

**Interfaces:** `ComparingEngine(EvaluationEngine primary, EvaluationEngine secondary)`; `name()` is `compare`; `status()` is the primary's; `evaluate` runs both when the secondary is ready, logs `WARN` with the triple counts and up to 5 differing triples when `!sameResults`, always returns the primary's outcome.

- [ ] **Step 1: Tests:** stub engines: identical graphs log nothing; different graphs log a warning (capture with a Logback `ListAppender`); a not-ready secondary is skipped with one log line, primary returned.
- [ ] **Step 2:** Implement; wire `compare` in `EngineConfig`; `mvn test` green. Commit `feat: compare mode`.

---

# Phase 3: The Jena rule engine

Rule files are new files in `src/main/resources/rules/`; the existing `tio_all.rules` stays untouched until Task 17. The engine reads functions in their specification form (`?s fn ( args )`, TR292C §2) and uses the stages in the design (5.5).

### Task 10: Spike: negation across chained stages

**Files:** Create `src/test/java/org/tmforum/intent/unit/RuleStagesSpikeTest.java`; append the decision to the design doc §5.5.

- [ ] **Step 1: Test:** a three-element list `?c log:allOf ( ex:a ex:b ex:c )` where members get `rdf:value` from stage A; stage B (`createInfModel(stageB, stageA)`) must derive `?c rdf:value true` only when all are true and `?c rdf:value false` otherwise, for 0, 1, 2 and 3 true members, with members' values arriving in different orders.
- [ ] **Step 2:** Implement the rules in a throwaway rule string using list-cell propagation (`(?l rdf:rest ?r) (?l tmf:cellOf ?fn) -> (?r tmf:cellOf ?fn)`) and `noValue`. If results are order-dependent, try saturating stage A into a fresh `Model` before stage B.
- [ ] **Step 3:** Record in the design doc which chaining works (or the fallback chosen). Commit `test: spike on stage chaining and negation`.

### Task 11: Rule engine skeleton

**Files:** Create `engine/OntologyModels.java`, `engine/ContractExtractor.java`, `engine/ResultAdapter.java`; Modify `engine/JenaEvaluationEngine.java`, `handler/JenaBuiltins.java` (register helper only); Test `unit/JenaEngineSkeletonTest.java`.

**Interfaces:**
- `OntologyModels`: `Optional<Model> load(Path dir)`: parses every `*.ttl` in `ontology/` except `intent.ttl`, cached; empty if the directory has no TTL.
- `ContractExtractor.extract(InfModel stageB): Model` = the contract graph (uses `ResultContract.filter` plus chosen-member selection via marker `tmf:chosenFor`).
- `ResultAdapter.adapt(Model resultGraph, Model expression): EvaluationResult` builds state (intent value → `Fulfilled`/`Degraded`), reason string identical in format to Java (`Conditions not met: a; b`), and the flat condition list (type, passed, observed, bound, operator, selected) for `HandlerStateWriter`/`BestEffortLimits`.
- `JenaEvaluationEngine.evaluate`: base `Model` = ontology ∪ expression ∪ observations ∪ resources ∪ held facts; stage A reasoner (`GenericRuleReasoner` over the stage A rule files, `FORWARD_RETE`), stage B reasoner over stage A's `InfModel`; extract; adapt. `status()`: not ready with reason `"conformance suite not passing"` (a `static final boolean READY = false` flipped in Task 17) or `"ontology files not found in ontology/"`.

- [ ] **Step 1: Tests** (`assumeTrue(ontology present)`): status not ready with a clear reason; `evaluate` on an expression with an intent but no rules matching returns a graph that contains no `icm:result`; `OntologyModels.load` finds `icm:DeliveryExpectation rdfs:subClassOf icm:Expectation` (no `intent.ttl` duplicate); missing directory gives `Optional.empty()` without exception.
- [ ] **Step 2:** Run, expect FAIL; implement; `ConformanceTest` still passes for `java` and now runs the Jena column as skipped (not ready). Commit `feat: rule engine skeleton`.

### Task 12: Stage A: quantity functions, units, newest observation

**Files:** Create `src/main/resources/rules/tio_a_quantity.rules`; Modify `handler/JenaBuiltins.java`, `engine/JenaEvaluationEngine.java` (load the file); Test `unit/JenaBuiltinsTest.java`, `unit/JenaQuantityRulesTest.java`, corpus cases.

**Interfaces:** builtins registered by `JenaBuiltins.register()`:
- `unitCompare(?va, ?ua, ?vb, ?ub, ?cmp)`: binds `?cmp` to -1/0/1 using `Quantities.compare`; fails on a unit mismatch.
- `unitMismatch(?ua, ?ub)`: succeeds when `Quantities.parse` dimensions differ.
- `newestObservation(?metric, ?obs)`: binds the `met:Observation` of `?metric` with the greatest `met:obtainedAt` in the graph being reasoned over.

- [ ] **Step 1: Tests:** `JenaBuiltinsTest` for each builtin (same cases as `QuantitiesTest`, prefix conversion, mismatch fails, newest of three observations). `JenaQuantityRulesTest` using spec-form Turtle `ex:c quan:smaller ( ex:Latency [ rdf:value 25 ; quan:unit "ms" ] )`: true/false for each of `atLeast, atMost, greater, smaller, exactly, inRange`; a metric argument resolves through its newest observation; `[ met:lastValue ( ex:m ) ]` as an argument resolves the same way; literal arguments are unitless quantities; mismatch yields `false` plus a helper fact the reason rule uses.
- [ ] **Step 2:** Run, expect FAIL; implement the builtins and the rules (for each comparison function: read arguments with `listEntry`, derive argument value/unit helper facts per argument kind, then derive the comparison). Corpus: every `quantity-*` case must now pass on `jena` once the stage B default-false rule exists (Task 13); until then run the stage A rules only in the unit test.
- [ ] **Step 3:** Commit `feat: rule engine quantity functions with units`.

### Task 13: Stage B: defaults, combination and logical operators

**Files:** Create `src/main/resources/rules/tio_b_logic.rules`; Test `unit/JenaLogicRulesTest.java`, corpus cases (`logic-allof`, `logic-anyof`, `logic-noneof`, `logic-oneof`, `logic-match`).

- [ ] **Step 1: Tests** (spec-form lists, chained stages from Task 10): `allOf/anyOf/noneOf/oneOf` truth tables for 0 to 3 true members; non-boolean arguments are dropped; multiple functions on one `icm:Condition` combine by conjunction; a condition with no contributions is `false`; `log:match`, `matchAll`, `matchAny`, `matchStatement` against the knowledge base; every `icm:Intent`/`icm:Expectation`/`icm:Condition` IRI ends with exactly one `rdf:value`.
- [ ] **Step 2:** Run, expect FAIL; implement the rules using the pattern chosen in Task 10 (list-cell propagation, `noValue` in stage B). Explicit false is derived by a final rule (`noValue(?e rdf:value true)` on typed elements).
- [ ] **Step 3:** Commit `feat: rule engine logical operators and boolean defaults`.

### Task 14: Stage A: sets and pools

**Files:** Create `src/main/resources/rules/tio_a_sets.rules`; Test `unit/JenaSetRulesTest.java`, corpus cases (`pool-*`).

- [ ] **Step 1: Tests:** `set:resourcesOfType ( ex:C )` includes subclass instances (ontology/`rdfs:subClassOf` in the data); `set:resourcesWithPropertyObject ( ex:p ex:a ex:b )`; the container-argument extension (`( ex:p ex:Selected )` where `ex:Selected` is an `icm:Target`, expands to its members); `set:resourcesWithProperty`, `set:valuesOfObjectProperty`; `set:intersection/union/difference` over lists of anonymous function nodes; a pool filtered by another pool is recomputed as the other pool's members appear (dynamic containers); `set:isMember/elementOf/includedIn/intersectsWith/forAll/empty` truth values.
- [ ] **Step 2:** Run, expect FAIL; implement (negation-free where possible; `set:difference` in stage B).
- [ ] **Step 3:** Commit `feat: rule engine set functions`.

### Task 15: Stage B: delivery, `chooseFrom`, held resources, roll-up

**Files:** Create `src/main/resources/rules/tio_b_delivery.rules` (successor of `tio_choose.rules`); Test `unit/JenaDeliveryRulesTest.java`, corpus (`delivery-*`, `seed-*`).

- [ ] **Step 1: Tests:** the four `JenaRulesTest` choose cases (lowest candidate, wrong type, already satisfied, per expectation) move here; a held resource of the delivery type is chosen first and the pool is not consulted; the C-tag pool follows the chosen S-tag with no ordering requirement between the two expectations; delivery expectation value `true` iff a target member has the delivery type; the intent and expectations roll up through `log:allOf`; the whole seed intent against `TestInputs.inventory()` matches the `seed-*` corpus on `jena`.
- [ ] **Step 2:** Run, expect FAIL; implement; chosen members carry `tmf:chosenFor` so `ContractExtractor` can output only chosen ones (Review Focus 4: assert a 1,000-UNI inventory yields exactly one `rdfs:member` triple).
- [ ] **Step 3:** Commit `feat: rule engine delivery expectations and chooseFrom`.

### Task 16: Reasons, observations, adapter equivalence

**Files:** Create `src/main/resources/rules/tio_b_reasons.rules`; Modify `engine/ResultAdapter.java`; Test `unit/ResultAdapterTest.java`, corpus (`quantity-no-observation`, `quantity-unit-mismatch`, `delivery-wrong-type`).

- [ ] **Step 1: Tests:** reasons have identical text to Java for no observation, unit mismatch, no candidate; `met:observed` links and copies the observation triples; `ResultAdapter.adapt` of the Jena graph and the Java engine's own `EvaluationResult` agree on state, reason string and (type, passed, operator, observed, bound, selected) for every corpus case; `BestEffortLimits` produces the same proposal from both.
- [ ] **Step 2:** Run, expect FAIL; implement; the adapter derives `observed`/`bound`/`operator` from the graph plus the expression.
- [ ] **Step 3:** Commit `feat: rule engine reasons and result adapter`.

---

# Phase 4: Switch on

### Task 17: Readiness

**Files:** Modify `engine/JenaEvaluationEngine.java`; Test `conformance/ReadinessGuardTest.java`.

- [ ] **Step 1: Test:** `ReadinessGuardTest` asserts `JenaEvaluationEngine.READY == (every non-skipped ConformanceTest case passes for jena)`; with the ontology present it fails if READY is true and a case fails, or READY is false and all pass.
- [ ] **Step 2:** `mvn test -Dtest=ConformanceTest` passes every case for `jena` locally; set `READY = true`; guard passes.
- [ ] **Step 3:** Run `intent.evaluator=compare` locally (`mvn test -Dintent.evaluator=compare -Dtest='*IT'` or the Access demo) and confirm no WARN differences; `intent.evaluator=jena` passes `TioReportIT`/`AccessDemoIT` locally.
- [ ] **Step 4:** Commit `feat: rule engine ready`.

### Task 18: Cleanup and documentation

**Files:** Modify `config/JenaConfig.java` (remove the unused `ruleReasoner` bean), delete `src/main/resources/rules/tio_all.rules` and `tio_choose.rules`, `ontology/jena-rules/tio_all.rules` copy; update `JenaRulesTest` to the new files; Modify `README.md`, `CLAUDE.md`, the design doc status; `docs/Access_HSI_Demo.md` (add how to run with `intent.evaluator=jena|compare`).

- [ ] **Step 1:** Decide with the owner whether to keep `HandlerStateWriter`'s `handlerState` graph (record the decision in the design doc); if retired, remove it and its doc references.
- [ ] **Step 2:** `mvn test` and the integration suite green (baseline gate).
- [ ] **Step 3:** Update CLAUDE.md to describe the real design (engine seam, result graph, handler-built TR290 reports); close `bbf921j-4qm` (`bd close bbf921j-4qm`).
- [ ] **Step 4:** Commit `docs: describe the selectable engine design; remove the unused reasoner bean`.

---

## Self-Review

- **Spec coverage:** seam/selection/health (T1-2); contract and graph storage (T3-4); handler turns graph into TR290 reports (T5-6); conformance suite, deviation register, compare mode (T7-9); rule engine stages 0/A/B, builtins, chooseFrom, held, units, reasons (T10-16); readiness gate, cleanup, docs, bead (T17-18); baseline (T0); decisions 1-8 and the two review answers are in Global Constraints and Tasks 8, 14.
- **Left deliberately to the implementer:** rule text inside each stage file (the tests and corpus pin behaviour), exact class/package internals, reason wording beyond the cases listed, whether `/health` details are additional fields.
- **Type consistency:** `EvaluationOutcome(Model resultGraph, EvaluationResult result)` is used by T1, T2, T3, T5, T6, T7, T9; `ReportBuilder.build(Model, Model, ReportContext)` is defined in T5 and consumed in T6; `ResultContract.sameResults` is defined in T3 and used in T7, T9; `JenaEvaluationEngine.READY` is defined in T11 and flipped in T17.
- **Risks tracked:** negation across stages (T10 decides), `BestEffortLimits` condition details (T16), ontology load cost per evaluation (measure in T11; cache `OntologyModels`).

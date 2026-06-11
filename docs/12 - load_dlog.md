# Dlog → Apache Jena Rules Translation

## Background

`ontology/rules/` contains 15 Datalog rule files written for **RDFox**, an in-memory
RDF reasoner with Datalog+ support. Apache Jena Fuseki does not execute RDFox Datalog
directly. Jena has its own rule language (`GenericRuleReasoner`) that covers the same
forward-chaining semantics for the patterns used here.

This document covers:
1. Syntax differences and the translation mapping
2. Rules that cannot be translated directly (FORALL, NAF, set-difference)
3. File inventory
4. How to load the Jena rules in Fuseki via assembler configuration
5. Limitations of this PoC approach

---

## 1. Syntax comparison

| Concept | RDFox / dlog | Apache Jena |
|---|---|---|
| Prefix declaration | `PREFIX ns: <uri>` | `@prefix ns: <uri> .` |
| Comment | `%` or `#` | `#` |
| Rule direction | `[head] :- [body]` (head first) | `[name: body -> head]` (body first) |
| Triple pattern | `[?s, ?p, ?o]` | `(?s ?p ?o)` |
| Multiple body patterns | comma-separated | space or newline separated |
| Arithmetic filter | `FILTER(?x >= ?y)` | `ge(?x, ?y)` (built-in) |
| Less-than | `FILTER(?x < ?y)` | `lt(?x, ?y)` |
| Greater-than | `FILTER(?x > ?y)` | `gt(?x, ?y)` |
| Less-or-equal | `FILTER(?x <= ?y)` | `le(?x, ?y)` |
| Equality | `FILTER(?x = ?y)` | `equal(?x, ?y)` |
| Negation-as-failure | `NOT [?s, ?p, ?o]` | `noValue(?s, ?p, ?o)` |
| FORALL (universal) | `FORALL(?x)(... IMPLIES ...)` | **Not supported** — see §3 |
| Rule name | optional, in `[name, ...]` | optional, `[name: ...]` |

### Example

**dlog (head :- body):**
```
[?I, imo:imohandlingState, imo:imoStateCompliant] :-
    [?E, rdf:type, imo:imoComplies],
    [?E, imo:imoeventIssuedFor, ?I] .
```

**Jena (body -> head):**
```
[imoStateCompliant:
    (?E rdf:type imo:imoComplies)
    (?E imo:imoeventIssuedFor ?I)
    ->
    (?I imo:imohandlingState imo:imoStateCompliant)
]
```

---

## 2. Translation decisions

### Tautological rules dropped

Several dlog files contain identity rules of the form:
```
[?X, rdf:type, A] :- [?X, rdf:type, A] .
```
These assert only what is already known and produce no new inferences.
They are omitted from the Jena translation with a comment.

### FORALL / allOf compliance — approximated

`tio-rules.dlog` contains an RDFox FORALL rule:
```
[?intent, imo:intentHandlingState, imo:Complies] :-
    [?intent, rdf:type, icm:Intent],
    FORALL(?exp) (
        [?intent, icm:hasExpectation, ?exp] IMPLIES [?exp, imo:complies, "true"^^xsd:boolean]
    ) .
```
Jena forward-chaining rules have no FORALL construct. The Jena translation uses a
negation-as-failure (NAF) approximation:
- Assert `Complies` if the intent has at least one compliant expectation AND no
  expectation is known to be non-compliant.

This is a **closed-world approximation**. Under the open-world assumption of OWL, it
may over-assign `Complies`. It is sufficient for PoC evaluation.

### Set-difference — not expressible in forward rules

`tmf_set_ops_eval.dlog` notes that `setdifference` requires NAF (negation-as-failure
with universal scope), which Jena forward rules cannot express cleanly.
The Jena file includes a comment directing implementors to use SPARQL `NOT EXISTS`
for set-difference queries instead.

### inspalternative typo in source

`tmf_insp_eval.dlog` line 210 references `inspalternative` without the `insp:` prefix.
The Jena translation corrects this to `insp:inspalternative`.

### tmf_core_rules.dlog.txt

This file has a `.txt` extension and contains duplicate delivery-expectation rules
(one with a typo `icmdeliveryType` instead of `icm:deliveryType`). The unique, correct
rules have been merged into `tio_core.rules`. The duplicates and the typo variant are
dropped.

### tmf_functional_semantics.dlog and tmf_quantity_rules.dlog

Both files consist almost entirely of tautological identity rules. The one non-trivial
rule (Container membership propagation) is retained; the rest are dropped.

---

## 3. File inventory

| Jena file | Source dlog | Content |
|---|---|---|
| `tio_core.rules` | `tio-rules.dlog` + `tmf_core_rules.dlog.txt` | ProbeIntent→Intent, delivery expectation, compliance |
| `tmf_icm_eval.rules` | `tmf_icm_eval.dlog` | ICM subclass chains, expectation results, report types |
| `tmf_imo_eval.rules` | `tmf_imo_eval.dlog` | IMO event hierarchy, handling/update state derivation |
| `tmf_guarantee_eval.rules` | `tmf_guarantee_eval.dlog` | Guarantee type propagation, report state binding |
| `tmf_validity_eval.rules` | `tmf_validity_eval.dlog` | Validity propagation, report validity status |
| `tmf_logops_eval.rules` | `tmf_logops_eval.dlog` | Logical operators: anyOf, match, matchAll, matchAny |
| `tmf_quantity_eval.rules` | `tmf_quantity_eval.dlog` | Numeric comparisons: atLeast, atMost, greater, inRange |
| `tmf_set_ops_eval.rules` | `tmf_set_ops_eval.dlog` | Set: union, intersection, isMember, resourcesOfType |
| `tmf_ext_eval.rules` | `tmf_ext_eval.dlog` | Utility, preference, proposal type propagation |
| `tmf_insp_eval.rules` | `tmf_insp_eval.dlog` | Intent specification template hierarchy |
| `tmf_metrics_eval.rules` | `tmf_metrics_eval.dlog` + `tmf_metrics_rules.dlog` | Observation propagation, metric links |
| `tmf_mathfn_eval.rules` | `tmf_mathfn_eval.dlog` | Math function type propagation |
| `tio_all.rules` | All of the above | Master flat file — all prefixes + all rules |

---

## 4. Loading in Fuseki via assembler

To enable Jena rule reasoning in Fuseki, provide a custom assembler configuration.
Create `config/fuseki-reasoner.ttl`:

```turtle
@prefix rdf:    <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
@prefix rdfs:   <http://www.w3.org/2000/01/rdf-schema#> .
@prefix ja:     <http://jena.hpl.hp.com/2005/11/Assembler#> .
@prefix tdb2:   <http://jena.apache.org/2016/tdb#> .
@prefix fuseki: <http://jena.apache.org/fuseki#> .

<#service> rdf:type fuseki:Service ;
    fuseki:name "tmf921" ;
    fuseki:endpoint [ fuseki:operation fuseki:query   ; fuseki:name "sparql"  ] ;
    fuseki:endpoint [ fuseki:operation fuseki:update  ; fuseki:name "update"  ] ;
    fuseki:endpoint [ fuseki:operation fuseki:gsp-r   ; fuseki:name "data"    ] ;
    fuseki:endpoint [ fuseki:operation fuseki:gsp-rw  ; fuseki:name "data"    ] ;
    fuseki:dataset <#dataset> .

<#dataset> rdf:type ja:RDFDataset ;
    ja:defaultGraph <#inferredGraph> .

<#inferredGraph> rdf:type ja:InfModel ;
    ja:reasoner [
        ja:reasonerURL <http://jena.hpl.hp.com/2003/GenericRuleReasoner> ;
        ja:rulesFrom   <file:ontology/jena-rules/tio_all.rules>
    ] ;
    ja:baseModel <#baseGraph> .

<#baseGraph> rdf:type tdb2:GraphTDB2 ;
    tdb2:location "databases/tmf921" .
```

Start Fuseki with:
```bash
fuseki-server --conf config/fuseki-reasoner.ttl
```

**Note:** The `stain/jena-fuseki` Docker image uses `FUSEKI_DATASET_1` for simple TDB2
datasets without inference. To use the assembler approach, mount the config file and
start with `--conf` instead:

```yaml
# docker-compose.yml override
fuseki:
  image: stain/jena-fuseki:5.2.0
  command: --conf /fuseki/config/fuseki-reasoner.ttl
  volumes:
    - ./config:/fuseki/config
    - ./ontology:/fuseki/ontology
    - fuseki_data:/fuseki/databases
```

---

## 5. Limitations

- **No Datalog FORALL**: Universal quantification (`allOf` compliance) is approximated
  with NAF. Full correctness requires RDFox or a dedicated Datalog engine.
- **No set-difference in forward rules**: Use SPARQL `NOT EXISTS` for set-difference.
- **Numeric builtins require typed literals**: `ge()`, `le()` etc. operate on XSD numeric
  types. Untyped literals will not match.
- **Named graphs**: Jena's `GenericRuleReasoner` operates on a single base model, not
  multiple named graphs. Rules applied to the default graph only. The intent handler
  must merge the eval graph into a temporary single model before applying rules.
- **Incremental reasoning**: Jena forward rules re-derive on every change.
  For large expression graphs, consider applying rules selectively at evaluation time
  rather than keeping a persistent inferred model.

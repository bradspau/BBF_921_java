package org.tmforum.intent.graph;

import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;

/** TIO v3.6.0 namespace URIs and commonly-used resources/properties. */
public final class TioNamespaces {

    private TioNamespaces() {}

    public static final String QUAN = "http://tio.models.tmforum.org/tio/v3.6.0/QuantityOntology/";
    public static final String LOG  = "http://tio.models.tmforum.org/tio/v3.6.0/LogicalOperators/";
    public static final String SET  = "http://tio.models.tmforum.org/tio/v3.6.0/SetOperators/";
    public static final String ICM  = "http://tio.models.tmforum.org/tio/v3.6.0/IntentCommonModel/";
    public static final String IMO  = "http://tio.models.tmforum.org/tio/v3.6.0/IntentManagementOntology/";
    public static final String MET  = "http://tio.models.tmforum.org/tio/v3.6.0/MetricsAndObservations/";
    public static final String MF   = "http://tio.models.tmforum.org/tio/v3.6.0/MathFunctions/";
    public static final String IG   = "http://tio.models.tmforum.org/tio/v3.6.0/IntentGuaranteeOntology/";
    public static final String IV   = "http://tio.models.tmforum.org/tio/v3.6.0/IntentValidityOntology/";
    public static final String INSP = "http://tio.models.tmforum.org/tio/v3.6.0/IntentSpecification/";

    // ── Logical operators (used as properties on root nodes) ─────────────────
    public static final Property LOG_allOf          = p(LOG, "allOf");
    public static final Property LOG_anyOf          = p(LOG, "anyOf");
    public static final Property LOG_noneOf         = p(LOG, "noneOf");
    public static final Property LOG_oneOf          = p(LOG, "oneOf");
    public static final Property LOG_match          = p(LOG, "match");
    public static final Property LOG_matchAll       = p(LOG, "matchAll");
    public static final Property LOG_matchAny       = p(LOG, "matchAny");
    public static final Property LOG_matchNone      = p(LOG, "matchNone");
    public static final Property LOG_matchOne       = p(LOG, "matchOne");
    public static final Property LOG_matchStatement = p(LOG, "matchStatement");

    // ── Quantity types (both legacy quana* and short-name aliases) ────────────
    public static final Resource QUAN_atLeast    = r(QUAN, "quanatLeast");
    public static final Resource QUAN_atLeast2   = r(QUAN, "atLeast");
    public static final Resource QUAN_atMost     = r(QUAN, "quanatMost");
    public static final Resource QUAN_atMost2    = r(QUAN, "atMost");
    public static final Resource QUAN_greater    = r(QUAN, "quangreater");
    public static final Resource QUAN_greater2   = r(QUAN, "greater");
    public static final Resource QUAN_smaller    = r(QUAN, "quansmaller");
    public static final Resource QUAN_smaller2   = r(QUAN, "smaller");
    public static final Resource QUAN_exactly    = r(QUAN, "quanexactly");
    public static final Resource QUAN_exactly2   = r(QUAN, "exactly");
    public static final Resource QUAN_inRange    = r(QUAN, "quaninRange");
    public static final Resource QUAN_inRange2   = r(QUAN, "inRange");

    // Quantity predicate forms (same URIs used as predicates in expression Turtle)
    public static final Property QUAN_P_atLeast  = p(QUAN, "quanatLeast");
    public static final Property QUAN_P_atLeast2 = p(QUAN, "atLeast");
    public static final Property QUAN_P_atMost   = p(QUAN, "quanatMost");
    public static final Property QUAN_P_atMost2  = p(QUAN, "atMost");
    public static final Property QUAN_P_greater  = p(QUAN, "quangreater");
    public static final Property QUAN_P_greater2 = p(QUAN, "greater");
    public static final Property QUAN_P_smaller  = p(QUAN, "quansmaller");
    public static final Property QUAN_P_smaller2 = p(QUAN, "smaller");
    public static final Property QUAN_P_exactly  = p(QUAN, "quanexactly");
    public static final Property QUAN_P_exactly2 = p(QUAN, "exactly");
    public static final Property QUAN_P_inRange  = p(QUAN, "quaninRange");
    public static final Property QUAN_P_inRange2 = p(QUAN, "inRange");

    // ── ICM ──────────────────────────────────────────────────────────────────
    public static final Resource ICM_DeliveryExpectation = r(ICM, "DeliveryExpectation");
    public static final Resource ICM_PropertyExpectation = r(ICM, "PropertyExpectation");
    public static final Property ICM_result              = p(ICM, "result");
    public static final Property ICM_target              = p(ICM, "target");
    public static final Property ICM_deliveryType        = p(ICM, "deliveryType");

    // ── Metrics ──────────────────────────────────────────────────────────────
    public static final Resource MET_Observation     = r(MET, "Observation");
    public static final Property MET_observedMetric  = p(MET, "observedMetric");
    public static final Property MET_obtainedAt      = p(MET, "obtainedAt");
    public static final Resource MET_metlastValue    = r(MET, "metlastValue");
    public static final Resource MET_metobservedValue = r(MET, "metobservedValue");

    // ── Set operators (predicate forms) ──────────────────────────────────────
    public static final Property SET_P_resourcesOfType            = p(SET, "resourcesOfType");
    public static final Property SET_P_resourcesWithPropertyObject = p(SET, "resourcesWithPropertyObject");
    public static final Resource SET_setisMember     = r(SET, "setisMember");
    public static final Resource SET_setintersectsWith = r(SET, "setintersectsWith");
    public static final Resource SET_setincludedIn   = r(SET, "setincludedIn");
    public static final Resource SET_setforAll       = r(SET, "setforAll");
    public static final Resource SET_empty           = r(SET, "empty");
    public static final Resource SET_elementOf       = r(SET, "elementOf");

    // ── Validity ─────────────────────────────────────────────────────────────
    public static final Property IV_validIf         = p(IV, "ivvalidIf");
    public static final Property IV_isValid         = p(IV, "ivisValid");
    public static final Property IV_sameValidityAs  = p(IV, "ivsameValidityAs");
    public static final Resource IV_validityOf      = r(IV, "ivvalidityOf");

    // ── Guarantee ────────────────────────────────────────────────────────────
    public static final Resource IG_GuaranteeReport = r(IG, "igGuaranteeReport");
    public static final Property IG_igstate         = p(IG, "igstate");
    public static final Resource IG_stateCompliant  = r(IG, "igGuaranteeStateCompliant");
    public static final Property IG_accepted        = p(IG, "igaccepted");
    public static final Property IG_rejected        = p(IG, "igrejected");

    // ── Math functions ────────────────────────────────────────────────────────
    public static final Resource MF_logistic        = r(MF, "mflogistic");
    public static final Resource MF_poly            = r(MF, "mfpoly");
    public static final Resource MF_mapping         = r(MF, "mfmapping");
    public static final Resource QUAN_sum           = r(QUAN, "quansum");
    public static final Resource QUAN_difference    = r(QUAN, "quandifference");
    public static final Resource QUAN_division      = r(QUAN, "quandivision");
    public static final Resource QUAN_multiplication = r(QUAN, "quanmultiplication");

    // ── Operator metadata for diagnostics ────────────────────────────────────
    /** Return the comparison operator symbol for a quantity type URI. */
    public static String operatorSymbol(String typeUri) {
        return switch (typeUri.substring(typeUri.lastIndexOf('/') + 1)) {
            case "quanatLeast", "atLeast"  -> ">=";
            case "quanatMost",  "atMost"   -> "<=";
            case "quangreater", "greater"  -> ">";
            case "quansmaller", "smaller"  -> "<";
            case "quanexactly", "exactly"  -> "==";
            case "quaninRange", "inRange"  -> "inRange";
            default -> "?";
        };
    }

    /** True if the URI is a two-argument quantity condition type. */
    public static boolean isTwoArgQuan(String typeUri) {
        return typeUri != null && (typeUri.startsWith(QUAN)) &&
                !operatorSymbol(typeUri).equals("?") && !typeUri.endsWith("inRange");
    }

    /** True if the URI is a range quantity condition type. */
    public static boolean isRangeQuan(String typeUri) {
        String local = typeUri != null ? typeUri.substring(typeUri.lastIndexOf('/') + 1) : "";
        return local.equals("quaninRange") || local.equals("inRange");
    }

    /** All quantity predicate URIs that appear as predicates in expression Turtle. */
    public static final Property[] ALL_QUAN_PREDICATES = {
            QUAN_P_atLeast, QUAN_P_atLeast2, QUAN_P_atMost, QUAN_P_atMost2,
            QUAN_P_greater, QUAN_P_greater2, QUAN_P_smaller, QUAN_P_smaller2,
            QUAN_P_exactly, QUAN_P_exactly2, QUAN_P_inRange, QUAN_P_inRange2,
    };

    /** All quantity type resources (used to find typed condition nodes). */
    public static final Resource[] ALL_QUAN_TYPES = {
            QUAN_atLeast, QUAN_atLeast2, QUAN_atMost, QUAN_atMost2,
            QUAN_greater, QUAN_greater2, QUAN_smaller, QUAN_smaller2,
            QUAN_exactly, QUAN_exactly2, QUAN_inRange, QUAN_inRange2,
    };

    private static Property p(String ns, String local) {
        return ResourceFactory.createProperty(ns + local);
    }

    private static Resource r(String ns, String local) {
        return ResourceFactory.createResource(ns + local);
    }
}

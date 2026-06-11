#!/usr/bin/env bash
# Seed sample intents against the running API.
# Usage:
#   bash seed_data/seed.sh               # standalone, port 8000
#   bash seed_data/seed.sh http://localhost:8001   # access, port 8001

set -euo pipefail

BASE="${1:-http://localhost:8000}/tmf-api/intentManagement/v5"
BBF="http://broadband-forum.org/Intent#"

echo "Seeding intents → $BASE"

# ── IntentSpecification ────────────────────────────────────────────────────
SPEC=$(curl -sf -X POST "$BASE/intentSpecification" \
  -H "Content-Type: application/json" \
  -d '{
    "@type": "IntentSpecification",
    "name": "BBF HSI Service Specification",
    "version": "1.0",
    "lifecycleStatus": "ACTIVE",
    "description": "Specifies HSI service delivery expectations and performance KPIs"
  }')
SPEC_ID=$(echo "$SPEC" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])" 2>/dev/null || echo "unknown")
echo "  IntentSpec: $SPEC_ID"

# ── Generic bandwidth intent ───────────────────────────────────────────────
INTENT1=$(curl -sf -X POST "$BASE/intent" \
  -H "Content-Type: application/json" \
  -d "{
    \"@type\": \"Intent\",
    \"name\": \"Sample Bandwidth Intent\",
    \"description\": \"Downstream bandwidth ≥ 100 Mbps\",
    \"expression\": {
      \"@type\": \"TurtleExpression\",
      \"expressionValue\": \"@prefix quan: <http://tio.models.tmforum.org/tio/v3.6.0/QuantityOntology/> .\\n@prefix rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\\n@prefix xsd:  <http://www.w3.org/2001/XMLSchema#> .\\n_:c quan:atLeast ( <${BBF}DownstreamBandwidthMetric> [ rdf:value \\\"100\\\"^^xsd:decimal ] ) .\\n\"
    }
  }")
ID1=$(echo "$INTENT1" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])" 2>/dev/null || echo "unknown")
echo "  Intent 1 (bandwidth): $ID1"

# ── Activate it ───────────────────────────────────────────────────────────
curl -sf -X PATCH "$BASE/intent/$ID1" \
  -H "Content-Type: application/json" \
  -d '{"lifecycleStatus": "ACTIVE"}' > /dev/null
echo "  Intent 1 activated"

# ── Sample latency intent ──────────────────────────────────────────────────
INTENT2=$(curl -sf -X POST "$BASE/intent" \
  -H "Content-Type: application/json" \
  -d "{
    \"@type\": \"Intent\",
    \"name\": \"Sample Latency Intent\",
    \"description\": \"End-to-end latency < 25 ms\",
    \"expression\": {
      \"@type\": \"TurtleExpression\",
      \"expressionValue\": \"@prefix quan: <http://tio.models.tmforum.org/tio/v3.6.0/QuantityOntology/> .\\n@prefix rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\\n@prefix xsd:  <http://www.w3.org/2001/XMLSchema#> .\\n_:c quan:smaller ( <${BBF}LatencyMetric> [ rdf:value \\\"25\\\"^^xsd:decimal ] ) .\\n\"
    }
  }")
ID2=$(echo "$INTENT2" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])" 2>/dev/null || echo "unknown")
echo "  Intent 2 (latency): $ID2"

# ── Hub registration ───────────────────────────────────────────────────────
HUB=$(curl -sf -X POST "$BASE/hub" \
  -H "Content-Type: application/json" \
  -d '{"callback": "http://localhost:9999/notifications"}')
HUB_ID=$(echo "$HUB" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])" 2>/dev/null || echo "unknown")
echo "  Hub: $HUB_ID"

echo ""
echo "Done. API: $BASE"
echo "  GET $BASE/intent     — list all intents"
echo "  GET $BASE/intent/$ID1/intentReport  — view evaluation results"

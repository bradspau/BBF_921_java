---
name: service-test-generator
description: Generate multi-step scenario-based service tests from OpenAPI/Swagger specs with data chaining across endpoints.
---

# Service Test Generator

Generate end-to-end service test scenarios from OpenAPI/Swagger specifications. Unlike single-endpoint unit tests, this skill creates multi-step, data-chained test flows that validate real business workflows — CRUD lifecycles, authentication flows, and cross-resource operations.

## Trigger Phrases

Use this skill when the user asks for tasks like:
- "Generate service tests from this Swagger spec"
- "Create scenario tests from OpenAPI"
- "Write CRUD lifecycle tests for this API"
- "Generate end-to-end API flow tests"
- "Create test scenarios from swagger.json"
- "Build integration test flows from the API spec"
- "Generate multi-step API tests"
- "Test the full user registration and login flow"

## Use / Do Not Use

Use this skill for:
- Generating multi-step test scenarios from OpenAPI/Swagger specs
- Creating CRUD lifecycle tests (create → read → update → delete → verify)
- Building authentication flow tests (register → login → access → refresh → logout)
- Cross-resource scenario tests (create user → create order for user → check order)
- Data-chained tests where one step's output feeds the next step's input
- Contract validation against the spec during scenario execution

Do not use this skill for:
- Single-endpoint isolated tests (use `api-test-generator`)
- Browser/UI E2E tests (use `e2e-test-generator`)
- Security-specific payloads (use `security-test-generator`)
- Input boundary testing (use `input-validation-tester`)
- Load/performance testing

## Local Files In This Skill

- References:
  - `references/scenario-patterns.md`
  - `references/data-chaining-guide.md`
  - `references/openapi-parsing-guide.md`
- Examples: `examples/`

## Deterministic Execution Flow (Required)

Run these steps in order. Do not skip steps unless a documented fallback branch applies.

### 1. Discovery — Locate and Parse OpenAPI Spec

```bash
# Find OpenAPI/Swagger spec files
find . -maxdepth 4 -type f \( \
  -name "openapi.json" -o -name "openapi.yaml" -o -name "openapi.yml" -o \
  -name "swagger.json" -o -name "swagger.yaml" -o -name "swagger.yml" -o \
  -name "api-spec.json" -o -name "api-spec.yaml" \
\) | head -10
```

If no spec file found, check for:

```bash
# Live spec endpoint (common patterns)
grep -rn "swagger\|openapi\|/docs\|/api-docs\|/swagger-ui" --include="*.py" --include="*.js" --include="*.ts" --include="*.java" --include="*.yaml" --include="*.yml" . 2>/dev/null | head -15
```

Read and parse the spec to extract:
- **Info**: API title, version, base URL (servers)
- **Auth**: Security schemes (Bearer, API key, OAuth2, Basic)
- **Endpoints**: All paths with methods, parameters, request/response schemas
- **Models**: Schema definitions (components/schemas or definitions)
- **Tags**: Endpoint grouping by resource/domain

### 2. Build Resource Map

Group endpoints by resource and identify CRUD operations:

```
Resource: User (/api/users)
  CREATE  → POST   /api/users          (201, returns {id, name, email})
  LIST    → GET    /api/users           (200, returns [{id, name, email}])
  READ    → GET    /api/users/{id}      (200, returns {id, name, email})
  UPDATE  → PUT    /api/users/{id}      (200, returns {id, name, email})
  PATCH   → PATCH  /api/users/{id}      (200, returns {id, name, email})
  DELETE  → DELETE /api/users/{id}      (204, no content)

Resource: Order (/api/orders)
  CREATE  → POST   /api/orders          (201, returns {id, userId, items, total})
  READ    → GET    /api/orders/{id}     (200)
  LIST    → GET    /api/orders          (200, query: userId, status)
  CANCEL  → POST   /api/orders/{id}/cancel (200)
```

Identify cross-resource relationships:
- Order.userId → User.id
- OrderItem.productId → Product.id
- Payment.orderId → Order.id

### 3. Identify Scenario Patterns

Based on the resource map, detect applicable scenario patterns:

**Pattern A: CRUD Lifecycle**
Applicable when: CREATE + READ + UPDATE + DELETE exist for a resource.

```
1. CREATE resource → capture {id}
2. READ resource/{id} → verify created data
3. UPDATE resource/{id} → modify fields
4. READ resource/{id} → verify updated data
5. DELETE resource/{id} → verify 204
6. READ resource/{id} → verify 404
```

**Pattern B: Authentication Flow**
Applicable when: auth endpoints exist (login, register, token, logout, refresh).

```
1. REGISTER → create account → capture credentials
2. LOGIN → authenticate → capture {access_token, refresh_token}
3. ACCESS protected endpoint → use access_token → verify 200
4. ACCESS without token → verify 401
5. REFRESH → use refresh_token → capture new {access_token}
6. ACCESS with new token → verify 200
7. LOGOUT → invalidate session
8. ACCESS with old token → verify 401
```

**Pattern C: Cross-Resource Flow**
Applicable when: resources have foreign key relationships.

```
1. CREATE parent resource (User) → capture {userId}
2. CREATE child resource (Order) with userId → capture {orderId}
3. READ child → verify parent reference
4. LIST children filtered by parent → verify results
5. DELETE parent → verify cascade behavior on children
```

**Pattern D: State Machine Flow**
Applicable when: resource has status/state transitions (order lifecycle, ticket workflow).

```
1. CREATE resource → status: "draft"
2. SUBMIT → status: "pending"
3. APPROVE → status: "approved"
4. COMPLETE → status: "completed"
5. Verify invalid transitions are rejected (draft → completed: 400)
```

**Pattern E: Search and Filter Flow**
Applicable when: LIST endpoint supports query parameters.

```
1. CREATE multiple resources with distinct attributes
2. LIST with filter A → verify correct subset
3. LIST with filter B → verify correct subset
4. LIST with pagination → verify page metadata
5. LIST with sort → verify order
6. CLEANUP created resources
```

**Pattern F: Error and Edge Case Flow**
Always applicable — generated for every resource.

```
1. CREATE with missing required fields → 400
2. CREATE with invalid data types → 422
3. READ non-existent resource → 404
4. UPDATE non-existent resource → 404
5. DELETE non-existent resource → 404
6. CREATE duplicate (unique constraint) → 409
7. ACCESS without auth → 401
8. ACCESS with wrong role → 403
```

### 4. Load References (Only as Needed)

| Need | Read this file |
|---|---|
| Scenario pattern details | `references/scenario-patterns.md` |
| Data chaining between steps | `references/data-chaining-guide.md` |
| OpenAPI spec parsing details | `references/openapi-parsing-guide.md` |

### 5. Select Test Framework

Match the project's existing framework:

| Project language | Framework | HTTP client |
|---|---|---|
| Python | pytest (ordered with pytest-order or class-based) | httpx |
| JavaScript/TypeScript | vitest (sequential with describe blocks) | fetch/supertest |
| Java | JUnit 5 (@TestMethodOrder) | REST-assured |
| Go | go test (subtests with t.Run) | net/http |

If no project context, default to **pytest + httpx**.

### 6. Generate Scenario Test Code

For each detected scenario, generate a test class/module:

**Key code generation rules:**

1. **Data chaining via fixtures/variables** — step outputs stored and reused
2. **Ordered execution** — tests within a scenario run sequentially
3. **Schema validation** — response body validated against spec schemas
4. **Cleanup guaranteed** — teardown deletes created resources even on failure
5. **Auth handling** — token obtained once, reused across steps
6. **Base URL configurable** — from environment variable
7. **Spec-driven assertions** — status codes, response shapes, required fields all from spec

**Python (pytest) example structure:**

```python
import pytest
import httpx
import os

BASE_URL = os.environ.get("API_BASE_URL", "http://localhost:8000")


class TestUserCrudLifecycle:
    """Scenario: Full CRUD lifecycle for User resource.

    Source: OpenAPI spec paths /api/users, /api/users/{id}
    Pattern: CRUD Lifecycle
    """

    user_id: str = None
    auth_token: str = None

    @pytest.fixture(autouse=True, scope="class")
    def client(self):
        with httpx.Client(base_url=BASE_URL, timeout=30.0) as client:
            yield client

    @pytest.fixture(autouse=True, scope="class")
    def authenticate(self, client):
        """Obtain auth token for the scenario."""
        response = client.post("/auth/login", json={
            "username": os.environ["TEST_USER"],
            "password": os.environ["TEST_PASSWORD"],
        })
        assert response.status_code == 200
        TestUserCrudLifecycle.auth_token = response.json()["access_token"]

    def headers(self):
        return {"Authorization": f"Bearer {self.auth_token}"}

    def test_01_create_user(self, client):
        """Step 1: Create a new user."""
        payload = {
            "name": "Test User",
            "email": "testuser@example.com",
            "role": "viewer",
        }
        response = client.post("/api/users", json=payload, headers=self.headers())
        assert response.status_code == 201

        data = response.json()
        assert "id" in data
        assert data["name"] == payload["name"]
        assert data["email"] == payload["email"]

        # Chain: store id for subsequent steps
        TestUserCrudLifecycle.user_id = data["id"]

    def test_02_read_user(self, client):
        """Step 2: Read the created user and verify data."""
        response = client.get(
            f"/api/users/{self.user_id}", headers=self.headers()
        )
        assert response.status_code == 200

        data = response.json()
        assert data["id"] == self.user_id
        assert data["name"] == "Test User"

    def test_03_update_user(self, client):
        """Step 3: Update user fields."""
        payload = {"name": "Updated User"}
        response = client.put(
            f"/api/users/{self.user_id}",
            json=payload,
            headers=self.headers(),
        )
        assert response.status_code == 200
        assert response.json()["name"] == "Updated User"

    def test_04_verify_update(self, client):
        """Step 4: Verify update persisted."""
        response = client.get(
            f"/api/users/{self.user_id}", headers=self.headers()
        )
        assert response.status_code == 200
        assert response.json()["name"] == "Updated User"

    def test_05_delete_user(self, client):
        """Step 5: Delete the user."""
        response = client.delete(
            f"/api/users/{self.user_id}", headers=self.headers()
        )
        assert response.status_code == 204

    def test_06_verify_deleted(self, client):
        """Step 6: Verify user no longer exists."""
        response = client.get(
            f"/api/users/{self.user_id}", headers=self.headers()
        )
        assert response.status_code == 404
```

### 7. Generate Supporting Files

- **conftest.py** — Shared client, auth, base URL configuration
- **factories.py** — Spec-driven payload generators using faker
- **.env.test** — Environment variables template
- **pytest.ini / pyproject.toml** — Test ordering and marker configuration

### 8. Validate Against Spec

After generating tests, cross-check:

- Every endpoint in the spec is covered by at least one scenario step
- All required request fields are included in payloads
- All documented status codes are asserted somewhere
- All response schema fields are validated
- Auth requirements match spec security definitions

### 9. Output Summary Report

```markdown
## Service Test Generation Report

- **Spec source**: <path to openapi.yaml/json>
- **API version**: <from spec info.version>
- **Framework**: <pytest | vitest | JUnit | go test>
- **Resources discovered**: <count>
- **Scenarios generated**: <count>
- **Total test steps**: <count>

### Resource Map
| Resource | Endpoints | CRUD coverage |
|---|---|---|
| <name> | <count> | <C/R/U/D present> |

### Scenarios
| # | Scenario | Pattern | Steps | Resources |
|---|---|---|---|---|
| 1 | User CRUD Lifecycle | CRUD | 6 | User |
| 2 | Auth Flow | Authentication | 8 | Auth, User |
| 3 | Order Creation Flow | Cross-Resource | 5 | User, Product, Order |
| 4 | Order State Transitions | State Machine | 5 | Order |
| 5 | User Search & Filter | Search/Filter | 6 | User |
| 6 | User Error Cases | Error/Edge | 7 | User |

### Data Chain Map
\`\`\`
POST /users → {user_id} → GET /users/{user_id}
                         → POST /orders {userId: user_id} → {order_id}
                         → DELETE /users/{user_id}
POST /auth/login → {access_token} → (all authenticated requests)
\`\`\`

### Spec Coverage
| Metric | Count |
|---|---|
| Endpoints in spec | <n> |
| Endpoints covered by scenarios | <n> |
| Status codes documented | <n> |
| Status codes asserted | <n> |
| Uncovered endpoints | <list or "none"> |

### Generated Files
| File | Scenarios | Steps |
|---|---|---|
| <file> | <scenario names> | <count> |

### How to Run
\`\`\`bash
<exact run command>
\`\`\`

### Dependencies
\`\`\`bash
<install command>
\`\`\`
```

## Fallback Behavior (Explicit)

### Fallback A: No OpenAPI/Swagger Spec Found

Condition: No spec file found locally or via URL.

Action:
1. Check if the API has a live spec endpoint:
   - `GET /docs` (FastAPI)
   - `GET /swagger.json` (Swagger)
   - `GET /api-docs` (SpringDoc)
   - `GET /openapi.json`
2. If found, fetch and parse it
3. If not found, ask user to provide spec URL or file path
4. If no spec exists at all, fall back to `api-test-generator` which can work from source code

### Fallback B: Spec Has No Schema Definitions

Condition: Endpoints defined but request/response schemas are missing or empty.

Action:
1. Generate scenarios with structural steps but without schema validation
2. Mark response assertions as `TODO: add schema validation when spec is updated`
3. Use minimal assertions (status code only)
4. Warn that spec quality limits test quality

### Fallback C: No Auth Scheme in Spec

Condition: Spec has no security definitions.

Action:
1. Skip authentication scenario pattern
2. Generate all other scenarios without auth headers
3. Note that if the real API requires auth, tests will need manual auth configuration
4. Generate a placeholder auth fixture with TODO

### Fallback D: Single Resource API

Condition: Spec has only one resource with limited endpoints (e.g., only GET).

Action:
1. Generate what's possible (read-only scenario, error cases)
2. Skip CRUD lifecycle (incomplete operations)
3. Report limited scenario coverage

## Progressive Disclosure Rules

- Always parse the full spec first — understand the complete API surface
- Do not read reference files unless the scenario generation needs them
- Generate error/edge case scenarios for every resource, not just CRUD-complete ones
- Validate spec coverage after generation — report any uncovered endpoints

## Done Criteria

Consider this skill execution complete only when:

- OpenAPI/Swagger spec was located and parsed
- Resource map was built with endpoint grouping
- Cross-resource relationships were identified
- All applicable scenario patterns were detected and generated
- Data chaining between steps is explicit and correct
- Cleanup/teardown is guaranteed for created resources
- Schema validation assertions reference spec definitions
- Spec coverage report shows all endpoints are covered
- Summary report with run instructions is provided
- User is asked whether to write files or review first

## Resources

- Scenario patterns: `references/scenario-patterns.md`
- Data chaining guide: `references/data-chaining-guide.md`
- OpenAPI parsing guide: `references/openapi-parsing-guide.md`
- Examples: `examples/`

## Source Links

- [OpenAPI Specification](https://spec.openapis.org/oas/latest.html)
- [Swagger Editor](https://editor.swagger.io/)
- [pytest Ordering](https://pytest-dev.github.io/pytest-order/)
- [pytest Class-Based Tests](https://docs.pytest.org/en/stable/getting-started.html#group-multiple-tests-in-a-class)
- [httpx Documentation](https://www.python-httpx.org/)

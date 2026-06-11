# Data Chaining Guide

## What Is Data Chaining

Data chaining is passing output from one test step as input to the next:

```
POST /users → response.id → GET /users/{id} → response.email → POST /orders {userEmail}
```

Without data chaining, each test step would need hardcoded IDs, which breaks in dynamic environments.

## Chaining Strategies by Framework

### Python (pytest) — Class-Level State

```python
class TestOrderFlow:
    """Scenario steps share state via class variables."""

    user_id: str = None
    order_id: str = None
    access_token: str = None

    def test_01_create_user(self, client):
        response = client.post("/api/users", json=payload)
        TestOrderFlow.user_id = response.json()["id"]

    def test_02_create_order(self, client):
        response = client.post("/api/orders", json={
            "userId": self.user_id,  # chained from step 1
            "items": [{"productId": "abc", "quantity": 2}],
        })
        TestOrderFlow.order_id = response.json()["id"]

    def test_03_verify_order(self, client):
        response = client.get(f"/api/orders/{self.order_id}")
        assert response.json()["userId"] == self.user_id
```

**Why class variables**: pytest creates a new instance per test method, so instance variables (`self.x = ...`) don't persist. Class variables do.

**Ordering**: pytest runs methods in definition order within a class by default. For explicit ordering, use `pytest-order`:

```python
import pytest

@pytest.mark.order(1)
def test_01_create(self, client): ...

@pytest.mark.order(2)
def test_02_read(self, client): ...
```

### TypeScript (vitest) — Module-Level Variables

```typescript
import { describe, test, expect, beforeAll } from 'vitest'

describe('Order Flow', () => {
  let userId: string
  let orderId: string
  let token: string

  beforeAll(async () => {
    const res = await fetch(`${BASE_URL}/auth/login`, {
      method: 'POST',
      body: JSON.stringify(credentials),
    })
    token = (await res.json()).access_token
  })

  test('step 1: create user', async () => {
    const res = await fetch(`${BASE_URL}/api/users`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` },
      body: JSON.stringify(userPayload),
    })
    const data = await res.json()
    userId = data.id  // chained
    expect(res.status).toBe(201)
  })

  test('step 2: create order for user', async () => {
    const res = await fetch(`${BASE_URL}/api/orders`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` },
      body: JSON.stringify({ userId, items: [...] }),
    })
    orderId = (await res.json()).id  // chained
    expect(res.status).toBe(201)
  })
})
```

**Ordering**: vitest/jest `describe` blocks run tests sequentially by default within the block. Use `--sequence.concurrent=false` to ensure order.

### Java (JUnit 5) — @TestMethodOrder + Static Fields

```java
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderFlowTest {

    private String userId;
    private String orderId;

    @Test @Order(1)
    void step01_createUser() {
        userId = given()
            .body(userPayload)
            .post("/api/users")
            .then()
            .statusCode(201)
            .extract().path("id");
    }

    @Test @Order(2)
    void step02_createOrder() {
        orderId = given()
            .body(Map.of("userId", userId, "items", items))
            .post("/api/orders")
            .then()
            .statusCode(201)
            .extract().path("id");
    }
}
```

### Go — Subtests with Shared State

```go
func TestOrderFlow(t *testing.T) {
    var userID, orderID string

    t.Run("step1_create_user", func(t *testing.T) {
        resp := postJSON(t, "/api/users", userPayload)
        assert.Equal(t, 201, resp.StatusCode)
        userID = parseJSON(resp).Get("id").String()
    })

    t.Run("step2_create_order", func(t *testing.T) {
        payload := map[string]any{"userId": userID, "items": items}
        resp := postJSON(t, "/api/orders", payload)
        assert.Equal(t, 201, resp.StatusCode)
        orderID = parseJSON(resp).Get("id").String()
    })
}
```

## Common Data Points to Chain

| Source step | Captured field | Used in |
|---|---|---|
| POST /auth/login | `access_token` | Authorization header for all subsequent requests |
| POST /auth/login | `refresh_token` | POST /auth/refresh |
| POST /resource | `id` | GET/PUT/DELETE /resource/{id} |
| POST /users | `id` | POST /orders `{userId}` |
| POST /orders | `id` | POST /payments `{orderId}` |
| POST /uploads | `url` or `fileId` | PATCH /users `{avatarUrl}` |

## Cleanup Strategy

Always clean up created resources, even if a step fails:

### Python — Class-Level Teardown

```python
class TestOrderFlow:
    created_ids: list = []

    @classmethod
    def teardown_class(cls):
        """Delete all created resources in reverse order."""
        client = httpx.Client(base_url=BASE_URL)
        for resource_type, resource_id in reversed(cls.created_ids):
            client.delete(f"/api/{resource_type}/{resource_id}",
                         headers={"Authorization": f"Bearer {cls.access_token}"})
        client.close()

    def test_01_create_user(self, client):
        response = client.post("/api/users", json=payload, headers=self.headers())
        user_id = response.json()["id"]
        TestOrderFlow.created_ids.append(("users", user_id))
```

### TypeScript — afterAll

```typescript
describe('Order Flow', () => {
  const cleanupIds: Array<{ type: string; id: string }> = []

  afterAll(async () => {
    for (const { type, id } of cleanupIds.reverse()) {
      await fetch(`${BASE_URL}/api/${type}/${id}`, {
        method: 'DELETE',
        headers: { Authorization: `Bearer ${token}` },
      })
    }
  })
})
```

## Anti-Patterns

| Anti-pattern | Problem | Fix |
|---|---|---|
| Hardcoded IDs | Breaks in different environments | Chain from POST response |
| No cleanup | Pollutes DB, affects other tests | Always add teardown |
| Relying on DB state | Mystery guest — tests need pre-existing data | Create everything in test |
| Parallel execution | Scenarios must run sequentially | Use sequential mode |
| Shared state across test files | One scenario's failure cascades | Each file is self-contained |

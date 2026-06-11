# Scenario Patterns

## Pattern A: CRUD Lifecycle

The most common pattern. Tests the full lifecycle of a single resource.

### Detection Rules
- Resource has at least CREATE + READ + DELETE
- UPDATE is optional but recommended

### Step Template

```
Step 1: CREATE → POST /resource
  - Send valid payload (all required fields)
  - Assert 201
  - Assert response body contains id and echoes input
  - CAPTURE: resource_id = response.id

Step 2: READ → GET /resource/{resource_id}
  - Assert 200
  - Assert response body matches created data

Step 3: LIST → GET /resource
  - Assert 200
  - Assert created resource appears in list

Step 4: UPDATE → PUT/PATCH /resource/{resource_id}
  - Send modified payload
  - Assert 200
  - Assert response reflects changes

Step 5: VERIFY UPDATE → GET /resource/{resource_id}
  - Assert 200
  - Assert persisted data matches update

Step 6: DELETE → DELETE /resource/{resource_id}
  - Assert 204 (or 200)

Step 7: VERIFY DELETE → GET /resource/{resource_id}
  - Assert 404
```

### Variations
- **Soft delete**: After DELETE, GET returns resource with `deleted: true` or `status: "archived"`
- **Partial update (PATCH)**: Only modified fields sent, others unchanged
- **Bulk operations**: If spec has bulk endpoints, test bulk create → bulk delete

---

## Pattern B: Authentication Flow

Tests the complete auth lifecycle.

### Detection Rules
- Endpoints matching: `/auth/*`, `/login`, `/register`, `/signup`, `/token`, `/logout`, `/refresh`
- Security scheme defined in spec (Bearer, OAuth2)

### Step Template

```
Step 1: REGISTER → POST /auth/register
  - Send new user credentials
  - Assert 201
  - CAPTURE: user credentials

Step 2: LOGIN → POST /auth/login
  - Send credentials from step 1
  - Assert 200
  - Assert response contains access_token
  - CAPTURE: access_token, refresh_token

Step 3: AUTHENTICATED ACCESS → GET /protected-resource
  - Send Authorization: Bearer {access_token}
  - Assert 200

Step 4: UNAUTHENTICATED ACCESS → GET /protected-resource
  - Send no auth header
  - Assert 401

Step 5: INVALID TOKEN → GET /protected-resource
  - Send Authorization: Bearer invalid-token-xxx
  - Assert 401

Step 6: REFRESH → POST /auth/refresh
  - Send refresh_token
  - Assert 200
  - CAPTURE: new_access_token

Step 7: ACCESS WITH NEW TOKEN → GET /protected-resource
  - Send Authorization: Bearer {new_access_token}
  - Assert 200

Step 8: LOGOUT → POST /auth/logout
  - Send access_token
  - Assert 200/204

Step 9: ACCESS AFTER LOGOUT → GET /protected-resource
  - Send old access_token
  - Assert 401
```

---

## Pattern C: Cross-Resource Flow

Tests relationships between resources.

### Detection Rules
- Request/response schemas contain foreign key fields (userId, orderId, etc.)
- Path nesting: `/users/{userId}/orders`
- Schema references: `$ref: '#/components/schemas/User'` inside another schema

### Step Template

```
Step 1: CREATE PARENT → POST /users
  - CAPTURE: user_id

Step 2: CREATE CHILD → POST /orders
  - Send {userId: user_id, ...}
  - CAPTURE: order_id

Step 3: READ CHILD → GET /orders/{order_id}
  - Assert response.userId == user_id

Step 4: LIST CHILDREN BY PARENT → GET /orders?userId={user_id}
  - Assert created order appears in filtered list

Step 5: DELETE PARENT → DELETE /users/{user_id}
  - Observe cascade behavior:
    - Option A: Children also deleted → verify GET /orders/{order_id} returns 404
    - Option B: Deletion blocked → verify 409 (has dependent resources)
    - Option C: Children orphaned → verify GET /orders/{order_id} still returns 200

Step 6: CLEANUP → DELETE remaining resources
```

---

## Pattern D: State Machine Flow

Tests status/state transitions on a resource.

### Detection Rules
- Schema has `status` or `state` field with enum values
- Transition endpoints exist: `/orders/{id}/submit`, `/orders/{id}/approve`
- Or PATCH with status field

### Step Template

```
Step 1: CREATE → POST /orders → status: "draft"
  - CAPTURE: order_id

Step 2: VALID TRANSITION → POST /orders/{order_id}/submit
  - Assert 200
  - Assert status changed to "pending"

Step 3: VALID TRANSITION → POST /orders/{order_id}/approve
  - Assert 200
  - Assert status: "approved"

Step 4: INVALID TRANSITION → POST /orders/{order_id}/submit
  - Assert 400/409 (cannot submit an already approved order)

Step 5: TERMINAL STATE → POST /orders/{order_id}/complete
  - Assert 200
  - Assert status: "completed"

Step 6: INVALID FROM TERMINAL → POST /orders/{order_id}/approve
  - Assert 400/409 (completed orders cannot change state)
```

---

## Pattern E: Search and Filter Flow

Tests query capabilities.

### Detection Rules
- GET list endpoint with query parameters defined in spec
- Parameters like: `search`, `q`, `filter`, `status`, `sort`, `page`, `limit`, `offset`

### Step Template

```
Step 1: SEED DATA → Create 5+ resources with varied attributes
  - User A: role=admin, name="Alpha"
  - User B: role=viewer, name="Beta"
  - User C: role=admin, name="Charlie"
  - CAPTURE: all IDs

Step 2: FILTER → GET /users?role=admin
  - Assert only admin users returned (A, C)
  - Assert viewer users excluded (B)

Step 3: SEARCH → GET /users?search=alpha
  - Assert only matching user returned (A)

Step 4: PAGINATION → GET /users?page=1&limit=2
  - Assert 2 results returned
  - Assert pagination metadata (total, pages, has_next)

Step 5: SORT → GET /users?sort=name&order=asc
  - Assert alphabetical order

Step 6: COMBINED → GET /users?role=admin&sort=name&page=1&limit=1
  - Assert correct subset with correct order and page

Step 7: CLEANUP → Delete all seeded resources
```

---

## Pattern F: Error and Edge Case Flow

Always generated for every resource, regardless of available operations.

### Step Template

```
Step 1: CREATE with empty body → Assert 400
Step 2: CREATE with missing required fields → Assert 400/422
Step 3: CREATE with invalid field types → Assert 400/422
Step 4: CREATE with extra unknown fields → Assert 200 (ignored) or 400 (strict)
Step 5: READ non-existent ID → Assert 404
Step 6: READ with malformed ID → Assert 400/404
Step 7: UPDATE non-existent resource → Assert 404
Step 8: DELETE non-existent resource → Assert 404
Step 9: Duplicate creation (unique constraint) → Assert 409
Step 10: Unauthorized access → Assert 401
Step 11: Forbidden access (wrong role) → Assert 403
```

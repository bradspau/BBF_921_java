# OpenAPI Parsing Guide

## Spec Versions

### OpenAPI 3.x (Current Standard)

```yaml
openapi: "3.0.3"
info:
  title: My API
  version: "1.0.0"
servers:
  - url: http://localhost:8000
    description: Local development
  - url: https://api.example.com
    description: Production
paths:
  /api/users:
    get: ...
    post: ...
  /api/users/{id}:
    get: ...
    put: ...
    delete: ...
components:
  schemas:
    User: ...
  securitySchemes:
    bearerAuth:
      type: http
      scheme: bearer
```

### Swagger 2.0 (Legacy)

```json
{
  "swagger": "2.0",
  "basePath": "/api",
  "paths": { ... },
  "definitions": { ... }
}
```

Key differences:
- `definitions` instead of `components/schemas`
- `basePath` instead of `servers`
- No `requestBody` — uses `in: body` parameters

## Extracting Key Information

### Base URL

```yaml
# OpenAPI 3.x
servers:
  - url: http://localhost:8000/api/v1

# Swagger 2.0
host: localhost:8000
basePath: /api/v1
schemes: [http, https]
```

Use first server URL or construct from host+basePath. Override with environment variable.

### Endpoints

```yaml
paths:
  /users:
    get:
      summary: List users
      operationId: listUsers
      tags: [Users]
      parameters:
        - name: page
          in: query
          schema: { type: integer, default: 1 }
      responses:
        '200':
          description: Success
          content:
            application/json:
              schema:
                type: array
                items: { $ref: '#/components/schemas/User' }
    post:
      summary: Create user
      operationId: createUser
      tags: [Users]
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/CreateUserRequest' }
      responses:
        '201':
          description: Created
          content:
            application/json:
              schema: { $ref: '#/components/schemas/User' }
        '400':
          description: Validation error
        '409':
          description: Duplicate email
```

Extract per endpoint:
- **Method + Path**: `POST /users`
- **Operation ID**: `createUser` (use for test naming)
- **Tags**: `Users` (use for resource grouping)
- **Parameters**: query, path, header params with types and required flag
- **Request body**: Schema reference + required flag
- **Responses**: Status codes + response schemas
- **Security**: Per-operation or global security requirements

### Schemas (Models)

```yaml
components:
  schemas:
    User:
      type: object
      required: [name, email]
      properties:
        id:
          type: string
          format: uuid
          readOnly: true
        name:
          type: string
          minLength: 2
          maxLength: 100
        email:
          type: string
          format: email
        role:
          type: string
          enum: [admin, editor, viewer]
          default: viewer
        createdAt:
          type: string
          format: date-time
          readOnly: true

    CreateUserRequest:
      type: object
      required: [name, email]
      properties:
        name:
          type: string
        email:
          type: string
        role:
          type: string
          enum: [admin, editor, viewer]
```

Extract per schema:
- **Required fields**: Must be in test payloads
- **Read-only fields**: Only in responses, not in request payloads
- **Enums**: Use for valid/invalid value testing
- **Format hints**: email, uuid, date-time → generate proper test data
- **Constraints**: minLength, maxLength, minimum, maximum → boundary values

### Auth Schemes

```yaml
components:
  securitySchemes:
    bearerAuth:
      type: http
      scheme: bearer
      bearerFormat: JWT
    apiKeyAuth:
      type: apiKey
      in: header
      name: X-API-Key
    oauth2:
      type: oauth2
      flows:
        authorizationCode:
          authorizationUrl: /oauth/authorize
          tokenUrl: /oauth/token
          scopes:
            read: Read access
            write: Write access

security:
  - bearerAuth: []  # Global default
```

Extract:
- Auth type (Bearer, API key, OAuth2)
- Header name and format
- Token endpoint (for test setup)
- Scopes (for permission testing)

## Resource Grouping Strategy

Group endpoints into resources using (in priority order):

1. **Tags**: Spec-defined grouping
2. **Path prefix**: `/api/users/*` → Users resource
3. **Operation ID prefix**: `listUsers`, `createUser` → Users resource

## Detecting Relationships

### Foreign Key Fields
Look for fields ending in `Id` or referencing other schemas:

```yaml
Order:
  properties:
    userId:
      type: string
      description: Reference to User
    items:
      type: array
      items:
        properties:
          productId:
            type: string
```

`userId` in Order → Order depends on User (cross-resource relationship).

### Nested Paths
```yaml
/users/{userId}/orders:     # Orders belong to Users
/orders/{orderId}/items:    # Items belong to Orders
```

### Schema References
```yaml
Order:
  properties:
    user:
      $ref: '#/components/schemas/User'  # Embedded relationship
```

## Generating Test Payloads from Schema

| Schema type | Test value |
|---|---|
| `string` | `"test_string"` |
| `string` + `format: email` | `"test@example.com"` |
| `string` + `format: uuid` | `uuid4()` |
| `string` + `format: date-time` | `datetime.utcnow().isoformat()` |
| `string` + `enum: [a, b, c]` | First enum value: `"a"` |
| `integer` | `1` |
| `integer` + `minimum: 0` | `0` (boundary) |
| `number` | `1.0` |
| `boolean` | `true` |
| `array` | `[<one valid item>]` |
| `object` | `{<all required fields>}` |

For required fields: always include.
For optional fields: include in happy path, omit in minimal tests.

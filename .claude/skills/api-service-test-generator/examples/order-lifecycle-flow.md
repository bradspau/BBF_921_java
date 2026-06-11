# Example: Order Lifecycle Service Test

## Scenario: User creates and cancels an order

Source:

- OpenAPI spec with `users`, `orders`, and `auth` resources
- Framework selected: pytest + httpx

Generated flow:

```python
import pytest


@pytest.mark.anyio
class TestOrderLifecycle:
    async def test_order_lifecycle(self, api_client, auth_headers):
        user_resp = await api_client.post(
            "/api/users",
            json={"email": "shopper@example.com", "name": "Shopper"},
            headers=auth_headers,
        )
        assert user_resp.status_code == 201
        user_id = user_resp.json()["id"]

        order_resp = await api_client.post(
            "/api/orders",
            json={"userId": user_id, "items": [{"sku": "SKU-1", "qty": 2}]},
            headers=auth_headers,
        )
        assert order_resp.status_code == 201
        order_id = order_resp.json()["id"]

        read_resp = await api_client.get(f"/api/orders/{order_id}", headers=auth_headers)
        assert read_resp.status_code == 200
        assert read_resp.json()["userId"] == user_id

        cancel_resp = await api_client.post(
            f"/api/orders/{order_id}/cancel",
            headers=auth_headers,
        )
        assert cancel_resp.status_code == 200
        assert cancel_resp.json()["status"] == "cancelled"
```

Validation points:

- Captures IDs from earlier steps and reuses them later.
- Verifies state transitions, not just status codes.
- Models a real business workflow instead of isolated endpoint checks.

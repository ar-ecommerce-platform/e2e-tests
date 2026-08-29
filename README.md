# e2e-tests

End-to-end API tests for the [ar-ecommerce-platform](https://github.com/ar-ecommerce-platform).
Drives the **running** platform through the gateway with REST Assured — the automated version of
`infra/scripts/demo-flow`.

## What it checks

`PlatformE2ETest` — one ordered scenario, all through `http://localhost:8080`:

1. `POST /api/auth/register` → 201
2. `POST /api/auth/login` → captures the bearer token
3. `GET /api/products` → captures two product ids
4. `POST /api/orders` → 201, `status: CONFIRMED`, positive `totalCents`; captures order + payment ids
5. `GET /api/payments/{id}` → `APPROVED`
6. `GET /api/notifications?userId=` → contains an `ORDER_CONFIRMED`
7. `GET /api/orders?userId=` → contains the order
8. unauthenticated `POST /api/orders` → 401
9. over-stock order (product 5, huge qty) → 409 `REJECTED_STOCK`
10. order over the payment ceiling → 402 `PAYMENT_FAILED`

## Run

```bash
# start the stack first
docker compose -f ../infra/compose/docker-compose.yml up -d

./gradlew test
./gradlew test -De2e.baseUrl=https://staging.example.com   # or another target
```

The suite **self-skips** (JUnit assumption) when the gateway isn't reachable, so `./gradlew build`
stays green in a pipeline that hasn't stood up the stack. A dedicated CI stage runs it for real
after `docker compose up`.

## Tech

Java 21 · JUnit 5 · REST Assured · AssertJ · Gradle. No Spring — it's a black-box HTTP client.

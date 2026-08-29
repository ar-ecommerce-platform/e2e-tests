# e2e-tests

End-to-end API tests for the [ar-ecommerce-platform](https://github.com/ar-ecommerce-platform).
Drives the **running** platform through the gateway with REST Assured — the automated version of
`infra/scripts/demo-flow`.

Covers: register → login → browse products → place order → payment → notification → list orders,
plus the failure paths (401 unauthenticated, 409 `REJECTED_STOCK`, 402 `PAYMENT_FAILED`).

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

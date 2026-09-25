package com.ecommerce.e2e;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.JSON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

import io.restassured.RestAssured;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The automated version of {@code infra/scripts/demo-flow}: register -> login -> browse -> order ->
 * payment -> notification, plus the 401 / 409 / 402 failure paths, all through the gateway.
 *
 * <p>Self-skips (JUnit assumption) when the gateway is not reachable, so it is safe in a build that
 * has no running stack. Point it elsewhere with {@code -De2e.baseUrl=...} or {@code E2E_BASE_URL}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
class PlatformE2ETest {

  private final String email = "e2e-" + System.currentTimeMillis() + "@example.com";
  private final String password = "Passw0rd!";

  private String token;
  private long productA;
  private long productB;
  private long orderId;
  private long paymentId;

  @BeforeAll
  void gatewayMustBeRouting() {
    RestAssured.baseURI = System.getProperty("e2e.baseUrl", "http://localhost:8080");
    int status;
    try {
      status =
          given()
              .contentType(JSON)
              .body(Map.of("email", "probe@example.com", "password", "x"))
              .post("/api/auth/register")
              .thenReturn()
              .statusCode();
    } catch (Exception notReachable) {
      // any failure to reach the gateway (incl. java.net.ConnectException, which is
      // a checked exception, not a RuntimeException) means "no stack" -> skip
      status = -1;
    }
    assumeThat(status)
        .as("gateway routing at %s (201/409 expected)", RestAssured.baseURI)
        .isIn(201, 409);
  }

  @Test
  @Order(1)
  void register() {
    given()
        .contentType(JSON)
        .body(Map.of("email", email, "password", password))
        .post("/api/auth/register")
        .then()
        .statusCode(201);
  }

  @Test
  @Order(2)
  void login() {
    token =
        given()
            .contentType(JSON)
            .body(Map.of("email", email, "password", password))
            .post("/api/auth/login")
            .then()
            .statusCode(200)
            .body("token", notNullValue())
            .extract()
            .path("token");
    assertThat(token).isNotBlank();
  }

  @Test
  @Order(3)
  void browseProducts() {
    JsonPath products = authed().get("/api/products").then().statusCode(200).extract().jsonPath();
    List<Object> ids = products.getList("id");
    assertThat(ids).hasSizeGreaterThanOrEqualTo(2);
    productA = products.getLong("[0].id");
    productB = products.getLong("[1].id");
  }

  @Test
  @Order(4)
  void placeOrder() {
    JsonPath order =
        authed()
            .contentType(JSON)
            .body(
                Map.of(
                    "items",
                    List.of(
                        Map.of("productId", productA, "quantity", 2),
                        Map.of("productId", productB, "quantity", 1))))
            .post("/api/orders")
            .then()
            .statusCode(201)
            .body("status", equalTo("CONFIRMED"))
            .extract()
            .jsonPath();
    orderId = order.getLong("id");
    paymentId = order.getLong("paymentId");
    assertThat(order.getLong("totalCents")).isPositive();
  }

  @Test
  @Order(5)
  void orderShowsApprovedPayment() {
    authed()
        .get("/api/orders/{id}", orderId)
        .then()
        .statusCode(200)
        .body("status", equalTo("CONFIRMED"))
        .body("paymentId", equalTo((int) paymentId));
  }

  @Test
  @Order(6)
  void notificationRecorded() {
    authed()
        .get("/api/notifications")
        .then()
        .statusCode(200)
        .body("type", hasItem("ORDER_CONFIRMED"));
  }

  @Test
  @Order(7)
  void orderListedForUser() {
    authed().get("/api/orders").then().statusCode(200).body("id", hasItem((int) orderId));
  }

  @Test
  @Order(8)
  void unauthenticatedOrderIsRejected() {
    given()
        .contentType(JSON)
        .body(Map.of("items", List.of(Map.of("productId", productA, "quantity", 1))))
        .post("/api/orders")
        .then()
        .statusCode(401);
  }

  @Test
  @Order(9)
  void overStockOrderIsRejected() {
    authed()
        .contentType(JSON)
        .body(Map.of("items", List.of(Map.of("productId", 5, "quantity", 9999))))
        .post("/api/orders")
        .then()
        .statusCode(409)
        .body("code", equalTo("REJECTED_STOCK"));
  }

  @Test
  @Order(10)
  void orderOverPaymentCeilingIsRejected() {
    authed()
        .contentType(JSON)
        .body(Map.of("items", List.of(Map.of("productId", productA, "quantity", 5))))
        .post("/api/orders")
        .then()
        .statusCode(402)
        .body("code", equalTo("PAYMENT_FAILED"));
  }

  @Test
  @Order(11)
  void anotherUserCannotSeeMyData() {
    String other = "e2e-other-" + System.currentTimeMillis() + "@example.com";
    given()
        .contentType(JSON)
        .body(Map.of("email", other, "password", password))
        .post("/api/auth/register")
        .then()
        .statusCode(201);
    String otherToken =
        given()
            .contentType(JSON)
            .body(Map.of("email", other, "password", password))
            .post("/api/auth/login")
            .then()
            .statusCode(200)
            .extract()
            .path("token");

    // Not "forbidden" but "not found": don't even confirm the order exists.
    as(otherToken).get("/api/orders/{id}", orderId).then().statusCode(404);
    // Claiming to be me via the identity header changes nothing - the gateway overwrites it.
    as(otherToken)
        .header("X-User-Id", email)
        .get("/api/orders")
        .then()
        .statusCode(200)
        .body("id", not(hasItem((int) orderId)));
    as(otherToken)
        .header("X-User-Id", email)
        .get("/api/notifications")
        .then()
        .statusCode(200)
        .body("size()", equalTo(0));
    // Internal endpoints are not reachable from outside at all.
    as(otherToken).get("/api/payments/{id}", paymentId).then().statusCode(403);
    as(otherToken).get("/api/users").then().statusCode(403);
  }

  @Test
  @Order(12)
  void apiDocsAreServedThroughTheGateway() {
    given().get("/swagger-ui/index.html").then().statusCode(200);
    // Each spec must point "Try it out" at the gateway, not at the service's private address.
    for (String service : List.of("auth", "products", "inventory", "orders", "notifications")) {
      given()
          .get("/api/{service}/api-docs", service)
          .then()
          .statusCode(200)
          .body("servers.url", hasItem("/api"));
    }
    // Internal endpoints stay out of the public docs.
    given().get("/api/users/api-docs").then().statusCode(200).body("paths", not(hasKey("/users")));
  }

  private RequestSpecification authed() {
    return as(token);
  }

  private static RequestSpecification as(String bearer) {
    return given().header("Authorization", "Bearer " + bearer);
  }
}

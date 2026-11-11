package com.kyro.gateway.filter;

import static org.junit.jupiter.api.Assertions.*;

import com.kyro.gateway.util.JwtUtils;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class AiIdentityFilterTest {
  private static final byte[] KEY_BYTES = new byte[32]; // Public, test-only fixture key.
  private static final SecretKey KEY = Keys.hmacShaKeyFor(KEY_BYTES);

  private JwtUtils jwt() {
    JwtUtils utils = new JwtUtils();
    ReflectionTestUtils.setField(utils, "jwtSecret", Base64.getEncoder().encodeToString(KEY_BYTES));
    return utils;
  }

  private String access(long id, boolean expired) {
    return Jwts.builder()
        .claim("id", id)
        .claim("roles", List.of("USER"))
        .id("fixture-access")
        .expiration(new Date(expired ? 1 : System.currentTimeMillis() + 60000))
        .signWith(KEY)
        .compact();
  }

  private ServerWebExchange forward(MockServerWebExchange exchange, String credential) {
    AtomicReference<ServerWebExchange> forwarded = new AtomicReference<>();
    new AiIdentityFilter(jwt(), credential)
        .apply(new AiIdentityFilter.Config())
        .filter(
            exchange,
            next -> {
              forwarded.set(next);
              return Mono.empty();
            })
        .block();
    return forwarded.get();
  }

  @Test
  void guestPublicRequestStripsEveryUntrustedIdentityHeader() {
    var exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/v1/ai/search?q=laptop")
                .header("X-User-Id", "42", "43")
                .header("X-User-Email", "forged")
                .header("X-User-Roles", "ADMIN")
                .header("X-Internal-Token", "forged"));
    var next = forward(exchange, "test-only-internal");
    assertNotNull(next);
    for (String name : List.of("X-User-Id", "X-User-Email", "X-User-Roles", "X-Internal-Token"))
      assertFalse(next.getRequest().getHeaders().containsKey(name));
  }

  @Test
  void guestViewGetsOnlyGatewayCredentialNotAClaimedAccount() {
    var exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/v1/ai/interactions/view")
                .header("X-User-Id", "42")
                .header("X-Internal-Token", "forged"));
    var headers = forward(exchange, "test-only-internal").getRequest().getHeaders();
    assertNull(headers.getFirst("X-User-Id"));
    assertEquals(List.of("test-only-internal"), headers.get("X-Internal-Token"));
  }

  @Test
  void realSignedAccessTokenAssertsItsOwnIdentityOnChatAndSse() {
    for (String path : List.of("/chat", "/chat/stream", "/search", "/chat/feedback")) {
      var exchange =
          MockServerWebExchange.from(
              MockServerHttpRequest.post("/api/v1/ai" + path)
                  .header(HttpHeaders.AUTHORIZATION, "Bearer " + access(42, false))
                  .header("X-User-Id", "999")
                  .header("X-Internal-Token", "forged"));
      var headers = forward(exchange, "test-only-internal").getRequest().getHeaders();
      assertEquals(List.of("42"), headers.get("X-User-Id"));
      assertEquals(List.of("test-only-internal"), headers.get("X-Internal-Token"));
    }
  }

  @Test
  void malformedExpiredRefreshAndInvalidIdTokensCannotDowngradeToGuest() {
    String refresh =
        Jwts.builder()
            .subject("fixture")
            .expiration(new Date(System.currentTimeMillis() + 60000))
            .signWith(KEY)
            .compact();
    for (String token :
        List.of("not-a-jwt", access(42, true), refresh, access(0, false), access(-1, false))) {
      var exchange =
          MockServerWebExchange.from(
              MockServerHttpRequest.get("/api/v1/ai/search?q=laptop")
                  .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
      assertNull(forward(exchange, "test-only-internal"));
      assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }
  }

  @Test
  void duplicateAuthorizationIsRejected() {
    var exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/v1/ai/search?q=laptop")
                .header(
                    HttpHeaders.AUTHORIZATION,
                    "Bearer " + access(42, false),
                    "Bearer " + access(43, false)));
    assertNull(forward(exchange, "test-only-internal"));
    assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
  }

  @Test
  void personalizedIsRestrictedToTheAuthenticatedAccount() {
    var own =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/v1/ai/recommendations/personalized/42")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + access(42, false)));
    assertNotNull(forward(own, "test-only-internal"));
    var other =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/v1/ai/recommendations/personalized/43")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + access(42, false)));
    assertNull(forward(other, "test-only-internal"));
    assertEquals(HttpStatus.FORBIDDEN, other.getResponse().getStatusCode());
    var guest =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/v1/ai/recommendations/personalized/42"));
    assertNull(forward(guest, "test-only-internal"));
    assertEquals(HttpStatus.UNAUTHORIZED, guest.getResponse().getStatusCode());
  }

  @Test
  void internalMutationAndUnexpectedMethodsAreNeverExposed() {
    for (String path :
        List.of("/products/sync", "/products/sync-backend/4", "/interactions/record", "/metrics")) {
      var exchange =
          MockServerWebExchange.from(
              MockServerHttpRequest.post("/api/v1/ai" + path)
                  .header(HttpHeaders.AUTHORIZATION, "Bearer " + access(42, false)));
      assertNull(forward(exchange, "test-only-internal"));
      assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }
    var wrongMethod = MockServerWebExchange.from(MockServerHttpRequest.delete("/api/v1/ai/search"));
    assertNull(forward(wrongMethod, "test-only-internal"));
  }

  @Test
  void missingCredentialFailsClosedButPublicGuestReadsStillWork() {
    var view =
        MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/ai/interactions/view"));
    assertNull(forward(view, ""));
    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, view.getResponse().getStatusCode());
    var guest =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/v1/ai/recommendations/trending"));
    assertNotNull(forward(guest, ""));
    assertFalse(guest.getResponse().getHeaders().containsKey("X-Internal-Token"));
  }
}

package com.kyro.gateway.filter;

import com.kyro.gateway.util.JwtUtils;
import io.jsonwebtoken.Claims;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Browser-facing AI allowlist. Never proxy the AI's internal mutation API with credentials. */
@Component
public class AiIdentityFilter extends AbstractGatewayFilterFactory<AiIdentityFilter.Config> {
  private static final String PREFIX = "/api/v1/ai";
  private static final Set<String> PUBLIC_GET =
      Set.of("/live", "/ready", "/health", "/products", "/search", "/recommendations/trending");
  private static final Set<String> PUBLIC_POST =
      Set.of("/chat", "/chat/stream", "/chat/feedback", "/search");
  private final JwtUtils jwtUtils;
  private final String internalToken;

  public AiIdentityFilter(
      JwtUtils jwtUtils, @Value("${internal.api.token:}") String internalToken) {
    super(Config.class);
    this.jwtUtils = jwtUtils;
    this.internalToken = internalToken;
  }

  @Override
  public GatewayFilter apply(Config config) {
    return (exchange, chain) -> {
      ServerHttpRequest request = exchange.getRequest();
      String path = request.getURI().getPath();
      if (!path.startsWith(PREFIX + "/")) {
        return reject(exchange, HttpStatus.FORBIDDEN, "Unsupported AI route");
      }
      String suffix = path.substring(PREFIX.length());
      HttpMethod method = request.getMethod();
      boolean view = HttpMethod.POST.equals(method) && suffix.equals("/interactions/view");
      boolean personalized =
          HttpMethod.GET.equals(method)
              && suffix.matches("/recommendations/personalized/[1-9][0-9]*");
      boolean publicRoute =
          (HttpMethod.GET.equals(method)
                  && (PUBLIC_GET.contains(suffix)
                      || suffix.matches(
                          "/recommendations/(similar|accessories|complementary)/[1-9][0-9]*")))
              || (HttpMethod.POST.equals(method) && PUBLIC_POST.contains(suffix));
      if (!view && !personalized && !publicRoute) {
        return reject(exchange, HttpStatus.FORBIDDEN, "Unsupported AI route");
      }

      Long userId = null;
      List<String> authorization = request.getHeaders().get(HttpHeaders.AUTHORIZATION);
      if (authorization != null) {
        if (authorization.size() != 1 || !authorization.get(0).startsWith("Bearer ")) {
          return reject(exchange, HttpStatus.UNAUTHORIZED, "Invalid access token");
        }
        String token = authorization.get(0).substring(7);
        try {
          if (!jwtUtils.validateToken(token)) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, "Invalid access token");
          }
          Claims claims = jwtUtils.getClaims(token);
          // Existing access tokens have id/roles/jti/exp; refresh tokens do not.
          if (!(claims.get("roles") instanceof List<?>)
              || claims.getId() == null
              || claims.getId().isBlank()
              || claims.getExpiration() == null) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, "Access token required");
          }
          userId = Long.parseLong(String.valueOf(claims.get("id")));
          if (userId <= 0) return reject(exchange, HttpStatus.UNAUTHORIZED, "Invalid access token");
        } catch (RuntimeException error) {
          return reject(exchange, HttpStatus.UNAUTHORIZED, "Invalid access token");
        }
      }
      if (personalized) {
        if (userId == null) return reject(exchange, HttpStatus.UNAUTHORIZED, "Login required");
        try {
          long requested = Long.parseLong(suffix.substring(suffix.lastIndexOf('/') + 1));
          if (requested != userId)
            return reject(exchange, HttpStatus.FORBIDDEN, "User identity mismatch");
        } catch (NumberFormatException error) {
          return reject(exchange, HttpStatus.FORBIDDEN, "User identity mismatch");
        }
      }
      boolean needsCredential = view || personalized || userId != null;
      if (needsCredential && (internalToken == null || internalToken.isBlank())) {
        return reject(
            exchange, HttpStatus.SERVICE_UNAVAILABLE, "AI internal authentication unavailable");
      }
      ServerHttpRequest.Builder trusted =
          request
              .mutate()
              .headers(
                  headers -> {
                    headers.remove("X-User-Id");
                    headers.remove("X-User-Email");
                    headers.remove("X-User-Roles");
                    headers.remove("X-Internal-Token");
                  });
      if (userId != null) trusted.header("X-User-Id", userId.toString());
      if (needsCredential) trusted.header("X-Internal-Token", internalToken);
      return chain.filter(exchange.mutate().request(trusted.build()).build());
    };
  }

  private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String detail) {
    exchange.getResponse().setStatusCode(status);
    exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
    String body = "{\"status\":" + status.value() + ",\"detail\":\"" + detail + "\"}";
    return exchange
        .getResponse()
        .writeWith(
            Mono.just(
                exchange
                    .getResponse()
                    .bufferFactory()
                    .wrap(body.getBytes(StandardCharsets.UTF_8))));
  }

  public static class Config {}
}

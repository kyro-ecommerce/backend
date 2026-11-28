package com.kyro.gateway.filter;

import com.kyro.gateway.util.JwtUtils;
import io.jsonwebtoken.Claims;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class AuthenticationFilter
    extends AbstractGatewayFilterFactory<AuthenticationFilter.Config> {

  private final JwtUtils jwtUtils;

  @Value("${internal.api.token:}")
  private String internalToken;

  public AuthenticationFilter(JwtUtils jwtUtils) {
    super(Config.class);
    this.jwtUtils = jwtUtils;
  }

  @Override
  public GatewayFilter apply(Config config) {
    return (exchange, chain) -> {
      ServerHttpRequest request = exchange.getRequest();

      if (HttpMethod.OPTIONS.equals(request.getMethod())) {
        return chain.filter(exchange);
      }

      String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
      if (authHeader == null) {
        return onError(exchange, "Missing Authorization Header", HttpStatus.UNAUTHORIZED);
      }

      if (!authHeader.startsWith("Bearer ")) {
        return onError(exchange, "Invalid Authorization Header format", HttpStatus.UNAUTHORIZED);
      }

      String token = authHeader.substring(7);

      if (!jwtUtils.validateToken(token)) {
        return onError(exchange, "Invalid or Expired JWT Token", HttpStatus.UNAUTHORIZED);
      }

      Claims claims = jwtUtils.getClaims(token);
      if (!"access".equals(claims.get("typ"))
          || !(claims.get("roles") instanceof List<?>)
          || claims.getId() == null
          || claims.getId().isBlank()
          || claims.getExpiration() == null) {
        return onError(exchange, "Access token required", HttpStatus.UNAUTHORIZED);
      }
      String userId = String.valueOf(claims.get("id"));
      try {
        if (Long.parseLong(userId) <= 0) throw new NumberFormatException();
      } catch (NumberFormatException error) {
        return onError(exchange, "Invalid user identity", HttpStatus.UNAUTHORIZED);
      }
      if (internalToken == null || internalToken.isBlank()) {
        return onError(
            exchange, "Internal authentication unavailable", HttpStatus.SERVICE_UNAVAILABLE);
      }
      String email = claims.getSubject();
      List<?> roles = claims.get("roles", List.class);
      String rolesStr =
          roles != null
              ? String.join(
                  ",",
                  roles.stream()
                      .filter(String.class::isInstance)
                      .map(String.class::cast)
                      .map(AuthenticationFilter::normalizeRole)
                      .toList())
              : "";

      if (config.requiredRole != null
          && !config.requiredRole.isBlank()
          && (roles == null
              || roles.stream()
                  .filter(String.class::isInstance)
                  .map(String.class::cast)
                  .map(AuthenticationFilter::normalizeRole)
                  .noneMatch(role -> normalizeRole(config.requiredRole).equals(role)))) {
        return onError(exchange, "Admin role required", HttpStatus.FORBIDDEN, "FORBIDDEN");
      }

      // Strip any untrusted user headers sent by client and inject verified headers from JWT
      ServerHttpRequest mutatedRequest =
          request
              .mutate()
              .headers(
                  h -> {
                    h.remove("X-User-Id");
                    h.remove("X-User-Email");
                    h.remove("X-User-Roles");
                    h.remove("X-Internal-Token");
                  })
              .header("X-Internal-Token", internalToken)
              .header("X-User-Id", userId)
              .header("X-User-Email", email)
              .header("X-User-Roles", rolesStr)
              .build();

      return chain.filter(exchange.mutate().request(mutatedRequest).build());
    };
  }

  private Mono<Void> onError(ServerWebExchange exchange, String errMessage, HttpStatus httpStatus) {
    return onError(exchange, errMessage, httpStatus, "UNAUTHORIZED");
  }

  private Mono<Void> onError(
      ServerWebExchange exchange, String errMessage, HttpStatus httpStatus, String code) {
    ServerHttpResponse response = exchange.getResponse();
    response.setStatusCode(httpStatus);
    response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);

    String jsonBody =
        String.format(
            "{\"type\":\"urn:problem-type:%s\",\"title\":\"%s\",\"status\":%d,\"detail\":\"%s\",\"code\":\"%s\",\"message\":\"%s\"}",
            code.toLowerCase(),
            httpStatus.getReasonPhrase(),
            httpStatus.value(),
            errMessage,
            code,
            errMessage);

    DataBuffer buffer = response.bufferFactory().wrap(jsonBody.getBytes(StandardCharsets.UTF_8));
    return response.writeWith(Mono.just(buffer));
  }

  private static String normalizeRole(String role) {
    String value = role.trim();
    return value.startsWith("ROLE_") ? value.substring(5) : value;
  }

  public static class Config {
    private String requiredRole;

    public String getRequiredRole() {
      return requiredRole;
    }

    public void setRequiredRole(String requiredRole) {
      this.requiredRole = requiredRole;
    }
  }

  @Override
  public List<String> shortcutFieldOrder() {
    return List.of("requiredRole");
  }
}

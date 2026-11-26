package com.kyro.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InternalTokenFilter extends OncePerRequestFilter {
  @Value("${internal.api.token:}")
  private String expectedToken;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (!request.getRequestURI().startsWith("/api/v1/internal/")
        && !request.getRequestURI().startsWith("/api/v1/orders")
        && !request.getRequestURI().startsWith("/api/v1/admin/")
        && request.getHeader("X-User-Id") == null
        && request.getHeader("X-User-Roles") == null) {
      filterChain.doFilter(request, response);
      return;
    }
    String provided = request.getHeader("X-Internal-Token");
    boolean valid =
        expectedToken != null
            && !expectedToken.isBlank()
            && provided != null
            && MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    if (!valid) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setContentType("application/problem+json");
      response
          .getWriter()
          .write(
              "{\"type\":\"urn:problem-type:invalid-internal-token\",\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"Valid"
                  + " internal token"
                  + " required\",\"code\":\"INVALID_INTERNAL_TOKEN\",\"message\":\"Valid internal"
                  + " token required\"}");
      return;
    }
    filterChain.doFilter(request, response);
  }
}

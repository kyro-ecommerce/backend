package com.kyro.auth.security.jwt;

import com.kyro.auth.security.userdetails.AppUserDetailsService;
import com.kyro.utils.ErrorResponseUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AuthTokenFilter extends OncePerRequestFilter {
  private static final Logger log = LoggerFactory.getLogger(AuthTokenFilter.class);
  private final JwtUtils jwtUtils;
  private final AppUserDetailsService userDetailsService;
  private final ErrorResponseUtils errorResponseUtils;

  public AuthTokenFilter(
      JwtUtils jwtUtils,
      AppUserDetailsService userDetailsService,
      ErrorResponseUtils errorResponseUtils) {
    this.jwtUtils = jwtUtils;
    this.userDetailsService = userDetailsService;
    this.errorResponseUtils = errorResponseUtils;
  }

  @Override
  protected void doFilterInternal(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull FilterChain filterChain)
      throws ServletException, IOException {
    try {
      String jwt = parseJwt(request);
      if (StringUtils.hasText(jwt) && jwtUtils.validateAccessToken(jwt)) {
        String username = jwtUtils.getEmailFromToken(jwt);
        UserDetails userDetails = userDetailsService.loadUserByUsername(username);
        var auth =
            new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
      }
    } catch (Exception e) {
      log.error("Lỗi xác thực JWT: {}", e.getMessage());
      sendErrorResponse(response);
      return;
    }
    filterChain.doFilter(request, response);
  }

  private void sendErrorResponse(HttpServletResponse response) throws IOException {
    errorResponseUtils.sendAuthenticationError(
        response, "Token truy cập không hợp lệ, vui lòng đăng nhập và thử lại!");
  }

  public String parseJwt(HttpServletRequest request) {
    String headerAuth = request.getHeader("Authorization");
    if (StringUtils.hasText(headerAuth) && headerAuth.startsWith("Bearer ")) {
      return headerAuth.substring(7);
    }
    return null;
  }
}

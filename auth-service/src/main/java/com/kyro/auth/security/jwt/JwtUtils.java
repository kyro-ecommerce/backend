package com.kyro.auth.security.jwt;

import com.kyro.auth.security.userdetails.AppUserDetails;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

@Component
public class JwtUtils {
  @Value("${auth.token.jwtSecret}")
  private String jwtSecret;

  @Value("${auth.token.accessExpirationInMils}")
  private Long expireTime;

  @Value("${auth.token.refreshExpirationInMils}")
  private Long refreshExpireTime;

  private SecretKey getSigningKey() {
    byte[] keyBytes = Decoders.BASE64.decode(jwtSecret);
    return Keys.hmacShaKeyFor(keyBytes);
  }

  public String generateAccessToken(Authentication authentication) {
    AppUserDetails userPrincipal = (AppUserDetails) authentication.getPrincipal();

    List<String> roles =
        userPrincipal.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();

    return Jwts.builder()
        .subject(userPrincipal.getEmail())
        .claim("typ", "access")
        .claim("id", userPrincipal.getId())
        .claim("roles", roles)
        .issuedAt(new Date())
        .expiration(new Date(System.currentTimeMillis() + expireTime))
        .id(UUID.randomUUID().toString())
        .signWith(getSigningKey())
        .compact();
  }

  public String generateRefreshToken(String email) {

    return Jwts.builder()
        .subject(email)
        .claim("typ", "refresh")
        .issuedAt(new Date())
        .expiration(new Date(System.currentTimeMillis() + refreshExpireTime))
        .id(UUID.randomUUID().toString())
        .signWith(getSigningKey())
        .compact();
  }

  public String getEmailFromToken(String token) {
    try {
      Claims claims =
          Jwts.parser().verifyWith(getSigningKey()).build().parseSignedClaims(token).getPayload();
      return claims.getSubject();
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid or expired JWT token", e);
    }
  }

  public boolean validateAccessToken(String token) {
    try {
      Claims claims =
          Jwts.parser().verifyWith(getSigningKey()).build().parseSignedClaims(token).getPayload();
      if (!"access".equals(claims.get("typ"))) {
        return false;
      }
      if (!(claims.get("roles") instanceof List<?>)) {
        return false;
      }
      if (claims.getId() == null || claims.getId().isBlank() || claims.getExpiration() == null) {
        return false;
      }
      Object idObj = claims.get("id");
      if (idObj == null) {
        return false;
      }
      long userId = Long.parseLong(String.valueOf(idObj));
      return userId > 0;
    } catch (JwtException | IllegalArgumentException e) {
      return false;
    }
  }

  public boolean validateRefreshToken(String token) {
    try {
      Claims claims =
          Jwts.parser().verifyWith(getSigningKey()).build().parseSignedClaims(token).getPayload();
      return "refresh".equals(claims.get("typ")) && claims.getExpiration() != null;
    } catch (JwtException | IllegalArgumentException e) {
      return false;
    }
  }
}

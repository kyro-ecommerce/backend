package com.kyro.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.kyro.auth.dto.AddAddressRequest;
import com.kyro.auth.security.jwt.JwtUtils;
import com.kyro.auth.security.otp.OtpService;
import com.kyro.auth.security.userdetails.AppUserDetails;
import com.kyro.exceptions.DomainException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.lang.reflect.Proxy;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

class AuthSecurityHardeningTest {

  private static final byte[] KEY_BYTES = new byte[32];
  private static final SecretKey KEY = Keys.hmacShaKeyFor(KEY_BYTES);

  private JwtUtils createJwtUtils() {
    JwtUtils jwtUtils = new JwtUtils();
    ReflectionTestUtils.setField(
        jwtUtils, "jwtSecret", Base64.getEncoder().encodeToString(KEY_BYTES));
    ReflectionTestUtils.setField(jwtUtils, "expireTime", 60_000L);
    ReflectionTestUtils.setField(jwtUtils, "refreshExpireTime", 300_000L);
    return jwtUtils;
  }

  private static UserRepository userRepoStub(Map<String, User> usersByEmail) {
    return (UserRepository)
        Proxy.newProxyInstance(
            UserRepository.class.getClassLoader(),
            new Class<?>[] {UserRepository.class},
            (proxy, method, args) -> {
              if ("findByEmail".equals(method.getName()) && args != null && args.length == 1) {
                return usersByEmail.get(args[0]);
              }
              if ("save".equals(method.getName()) && args != null && args.length == 1) {
                return args[0];
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }

  private static AddressRepository addressRepoStub(
      Map<Long, Address> addressesById, AtomicBoolean deleted) {
    return (AddressRepository)
        Proxy.newProxyInstance(
            AddressRepository.class.getClassLoader(),
            new Class<?>[] {AddressRepository.class},
            (proxy, method, args) -> {
              if ("findById".equals(method.getName()) && args != null && args.length == 1) {
                return Optional.ofNullable(addressesById.get(args[0]));
              }
              if ("delete".equals(method.getName())) {
                deleted.set(true);
                return null;
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }

  @Test
  void updateAndDeleteAddressRejectOtherUsersWithForbidden() {
    User owner = new User();
    owner.setId(1L);
    owner.setEmail("owner@kyro.test");

    User attacker = new User();
    attacker.setId(2L);
    attacker.setEmail("attacker@kyro.test");

    Address address = new Address();
    address.setId(99L);
    address.setUser(owner);

    AtomicBoolean deleted = new AtomicBoolean(false);
    UserService userService =
        new UserService(
            userRepoStub(Map.of("attacker@kyro.test", attacker)),
            null,
            null,
            null,
            addressRepoStub(Map.of(99L, address), deleted),
            createJwtUtils());

    AddAddressRequest req = new AddAddressRequest();
    req.setFullName("Attacker");

    DomainException updateEx =
        assertThrows(
            DomainException.class, () -> userService.updateAddress(99L, req, "attacker@kyro.test"));
    assertEquals(HttpStatus.FORBIDDEN, updateEx.getStatus());

    DomainException deleteEx =
        assertThrows(
            DomainException.class, () -> userService.deleteAddress(99L, "attacker@kyro.test"));
    assertEquals(HttpStatus.FORBIDDEN, deleteEx.getStatus());
    assertFalse(deleted.get());
  }

  @Test
  void jwtUtilsEnforcesExplicitTypClaim() {
    JwtUtils jwtUtils = createJwtUtils();
    AppUserDetails principal =
        new AppUserDetails(
            10L,
            "user@kyro.test",
            "pass",
            true,
            List.of(new SimpleGrantedAuthority("CUSTOMER")),
            null);
    var auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

    String accessToken = jwtUtils.generateAccessToken(auth);
    String refreshToken = jwtUtils.generateRefreshToken("user@kyro.test");
    String missingTypToken =
        Jwts.builder()
            .subject("user@kyro.test")
            .claim("id", 10L)
            .claim("roles", List.of("CUSTOMER"))
            .id("legacy-jti")
            .expiration(new Date(System.currentTimeMillis() + 60_000L))
            .signWith(KEY)
            .compact();

    assertTrue(jwtUtils.validateAccessToken(accessToken));
    assertFalse(jwtUtils.validateRefreshToken(accessToken));

    assertTrue(jwtUtils.validateRefreshToken(refreshToken));
    assertFalse(jwtUtils.validateAccessToken(refreshToken));

    assertFalse(jwtUtils.validateAccessToken(missingTypToken));
    assertFalse(jwtUtils.validateRefreshToken(missingTypToken));
  }

  @Test
  void otpServiceLocksAfterFiveFailuresAndEnforcesCooldown() {
    OtpService otpService = new OtpService(null, userRepoStub(Map.of()));
    ReflectionTestUtils.setField(otpService, "otpExpirationMinutes", 10);
    ReflectionTestUtils.setField(otpService, "resendCooldownMinutes", 1);

    String otp = otpService.generateOtp("Test@Kyro.Test");
    assertNotNull(otp);

    DomainException cooldownEx =
        assertThrows(DomainException.class, () -> otpService.generateOtp("test@kyro.test"));
    assertEquals(HttpStatus.TOO_MANY_REQUESTS, cooldownEx.getStatus());
    assertEquals("OTP_COOLDOWN", cooldownEx.getCode());

    for (int i = 0; i < 5; i++) {
      assertFalse(otpService.validateOtp("test@kyro.test", "000000"));
    }
    assertFalse(otpService.validateOtp("test@kyro.test", otp));
  }

  @Test
  void otpServiceAllowsOnlyOneConcurrentValidatorAndEvictsExpired() throws Exception {
    User user = new User();
    user.setEmail("race@kyro.test");
    user.setActive(false);

    OtpService otpService = new OtpService(null, userRepoStub(Map.of("race@kyro.test", user)));
    ReflectionTestUtils.setField(otpService, "otpExpirationMinutes", 10);
    ReflectionTestUtils.setField(otpService, "resendCooldownMinutes", 1);

    String otp = otpService.generateOtp("race@kyro.test");

    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      Future<Boolean> f1 =
          pool.submit(
              () -> {
                start.await();
                return otpService.validateOtp("race@kyro.test", otp);
              });
      Future<Boolean> f2 =
          pool.submit(
              () -> {
                start.await();
                return otpService.validateOtp("race@kyro.test", otp);
              });
      start.countDown();

      boolean r1 = f1.get();
      boolean r2 = f2.get();
      assertNotEquals(r1, r2, "Exactly one concurrent validation must succeed");
      assertTrue(r1 || r2);
    } finally {
      pool.shutdownNow();
    }

    ReflectionTestUtils.setField(otpService, "otpExpirationMinutes", -1);
    otpService.generateOtp("expired@kyro.test");
    Map<?, ?> storage = (Map<?, ?>) ReflectionTestUtils.getField(otpService, "otpStorage");
    assertNotNull(storage);
    assertEquals(1, storage.size());

    otpService.evictExpiredOtps();
    assertTrue(storage.isEmpty());
  }
}

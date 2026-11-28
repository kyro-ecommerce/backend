package com.kyro.auth;

import com.kyro.auth.dto.LoginRequest;
import com.kyro.auth.dto.RegisterRequest;
import com.kyro.auth.security.jwt.JwtUtils;
import com.kyro.auth.security.otp.OtpService;
import com.kyro.auth.security.userdetails.AppUserDetails;
import com.kyro.auth.security.userdetails.AppUserDetailsService;
import com.kyro.exceptions.AppException;
import com.kyro.exceptions.DomainException;
import com.kyro.utils.CookieUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("${api.prefix}/auth")
public class AuthController {

  private static final Logger log = LoggerFactory.getLogger(AuthController.class);

  private final JwtUtils jwtUtils;
  private final CookieUtils cookieUtils;
  private final AppUserDetailsService userDetailsService;
  private final AuthenticationManager authenticationManager;
  private final UserService userService;
  private final UserRepository userRepository;
  private final OtpService otpService;

  public AuthController(
      JwtUtils jwtUtils,
      CookieUtils cookieUtils,
      AppUserDetailsService userDetailsService,
      AuthenticationManager authenticationManager,
      UserService userService,
      UserRepository userRepository,
      OtpService otpService) {
    this.jwtUtils = jwtUtils;
    this.cookieUtils = cookieUtils;
    this.userDetailsService = userDetailsService;
    this.authenticationManager = authenticationManager;
    this.userService = userService;
    this.userRepository = userRepository;
    this.otpService = otpService;
  }

  @Value("${auth.token.refreshExpirationInMils}")
  private Long refreshTokenExpirationTime;

  @PostMapping("/login")
  public ResponseEntity<Map<String, Object>> authenticateUser(
      @Valid @RequestBody LoginRequest request, HttpServletResponse response) {
    Authentication authentication =
        authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword()));
    String accessToken = jwtUtils.generateAccessToken(authentication);
    String refreshToken = jwtUtils.generateRefreshToken(request.getEmail());
    cookieUtils.addRefreshTokenCookie(response, refreshToken, refreshTokenExpirationTime);

    AppUserDetails userDetails = (AppUserDetails) authentication.getPrincipal();
    User user = userRepository.findById(userDetails.getId()).orElseThrow();

    Map<String, Object> responseData = new HashMap<>();
    responseData.put("accessToken", accessToken);

    Map<String, Object> userMap = new HashMap<>();
    userMap.put("id", user.getId());
    userMap.put("email", user.getEmail());
    userMap.put("firstName", user.getFirstName());
    userMap.put("lastName", user.getLastName());
    userMap.put("role", user.getRole().getName().name());
    userMap.put("isActive", user.isActive());
    responseData.put("user", userMap);

    return ResponseEntity.ok(responseData);
  }

  @PostMapping("/register")
  public ResponseEntity<Map<String, String>> registerUser(
      @Valid @RequestBody RegisterRequest request) {
    userService.registerUser(request);
    return ResponseEntity.ok(
        Map.of("message", "Mã xác thực đã được gửi tới email. Vui lòng kiểm tra và xác thực."));
  }

  @PostMapping("/verification")
  public ResponseEntity<Map<String, String>> verifyOtp(
      @RequestBody OtpVerificationRequest request) {
    boolean isVerified = userService.verifyOtp(request);
    if (isVerified) {
      return ResponseEntity.ok(
          Map.of("message", "Xác thực thành công! Tài khoản đã được kích hoạt."));
    }
    throw new AppException("Mã OTP không hợp lệ hoặc đã hết hạn.");
  }

  @PostMapping("/refresh")
  public ResponseEntity<Map<String, String>> refreshAccessToken(HttpServletRequest request) {
    String refreshToken = cookieUtils.getRefreshTokenFromCookies(request);
    if (refreshToken != null && jwtUtils.validateRefreshToken(refreshToken)) {
      String usernameFromToken = jwtUtils.getEmailFromToken(refreshToken);
      UserDetails userDetails = userDetailsService.loadUserByUsername(usernameFromToken);
      String newAccessToken =
          jwtUtils.generateAccessToken(
              new UsernamePasswordAuthenticationToken(
                  userDetails, null, userDetails.getAuthorities()));

      Map<String, String> token = new HashMap<>();
      token.put("accessToken", newAccessToken);
      return ResponseEntity.ok(token);
    }
    throw new DomainException(HttpStatus.FORBIDDEN, "Refresh token không hợp lệ hoặc đã hết hạn.");
  }

  @PostMapping("/logout")
  public ResponseEntity<Map<String, String>> logout(HttpServletResponse response) {
    cookieUtils.deleteRefreshTokenCookie(response);
    return ResponseEntity.ok(Map.of("message", "Đăng xuất thành công!"));
  }

  @PostMapping("/verification/resend")
  public ResponseEntity<Map<String, String>> resendOtp(@RequestBody Map<String, String> request) {
    String email = request.get("email");
    if (email == null || email.isEmpty()) {
      throw new IllegalArgumentException("Email không được để trống.");
    }

    User user = userRepository.findByEmail(email);
    if (user == null) {
      throw new DomainException(HttpStatus.NOT_FOUND, "Email chưa được đăng ký hoặc không hợp lệ.");
    }

    if (!otpService.isResendAllowed(email)) {
      long remainingSeconds = otpService.getRemainingCooldownSeconds(email);
      String waitMessage =
          String.format("Vui lòng đợi %d giây trước khi yêu cầu mã OTP mới.", remainingSeconds);
      throw new DomainException(HttpStatus.TOO_MANY_REQUESTS, waitMessage);
    }

    String otp = otpService.generateOtp(email);
    otpService.sendOtpEmail(email, otp);
    return ResponseEntity.ok(
        Map.of("message", "Mã OTP mới đã được gửi tới email. Vui lòng kiểm tra hộp thư của bạn."));
  }

  @PostMapping("/password-reset")
  public ResponseEntity<Map<String, String>> forgotPass(
      @Valid @RequestBody ForgotPasswordRequest forgotPasswordRequest) {
    OtpVerificationRequest tmp = new OtpVerificationRequest();
    tmp.setEmail(forgotPasswordRequest.getEmail());
    tmp.setOtp(forgotPasswordRequest.getOtp());

    boolean isVerified = userService.verifyOtp(tmp);

    if (isVerified) {
      userService.forgotPassword(
          forgotPasswordRequest.getEmail(), forgotPasswordRequest.getNewPassword());
      return ResponseEntity.ok(Map.of("message", "Mật khẩu đã được thay đổi thành công!"));
    } else {
      throw new AppException("Mã OTP không hợp lệ hoặc đã hết hạn.");
    }
  }
}

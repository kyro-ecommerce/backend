package com.kyro.auth.security.otp;

import com.kyro.auth.User;
import com.kyro.auth.UserRepository;
import com.kyro.config.RabbitMQConfig;
import com.kyro.exceptions.DomainException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Service for generating, validating, and publishing OTP requests to RabbitMQ. */
@Service
public class OtpService {

  private static final Logger log = LoggerFactory.getLogger(OtpService.class);
  private static final int MAX_ATTEMPTS = 5;
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private final RabbitTemplate rabbitTemplate;
  private final UserRepository userRepository;

  public OtpService(RabbitTemplate rabbitTemplate, UserRepository userRepository) {
    this.rabbitTemplate = rabbitTemplate;
    this.userRepository = userRepository;
  }

  @Value("${app.otp.expiration-minutes:10}")
  private int otpExpirationMinutes = 10;

  @Value("${app.otp.resend-cooldown-minutes:1}")
  private int resendCooldownMinutes = 1;

  // In-memory OTP storage (normalized email -> immutable OtpData)
  private final ConcurrentHashMap<String, OtpData> otpStorage = new ConcurrentHashMap<>();

  /**
   * Generates a random 6-digit OTP code and caches it atomically, enforcing resend cooldown.
   *
   * @param email user email address
   * @return generated OTP code
   */
  public String generateOtp(String email) {
    String key = normalizeEmail(email);
    AtomicReference<String> generatedOtp = new AtomicReference<>();

    otpStorage.compute(
        key,
        (k, existing) -> {
          LocalDateTime now = LocalDateTime.now();
          if (existing != null) {
            LocalDateTime allowedResendTime =
                existing.generationTime().plusMinutes(resendCooldownMinutes);
            if (now.isBefore(allowedResendTime)) {
              long remainingSeconds =
                  Math.max(1, Duration.between(now, allowedResendTime).getSeconds());
              throw new DomainException(
                  HttpStatus.TOO_MANY_REQUESTS,
                  "OTP_COOLDOWN",
                  String.format(
                      "Vui lòng đợi %d giây trước khi yêu cầu mã OTP mới.", remainingSeconds));
            }
          }
          int otp = 100000 + SECURE_RANDOM.nextInt(900000);
          String otpString = String.valueOf(otp);
          generatedOtp.set(otpString);
          return new OtpData(otpString, now.plusMinutes(otpExpirationMinutes), now, 0);
        });

    return generatedOtp.get();
  }

  /**
   * Validates OTP code for the user email atomically and activates the account if valid.
   *
   * @param email user email address
   * @param otp OTP code
   * @return true if OTP is valid and not expired, false otherwise
   */
  public boolean validateOtp(String email, String otp) {
    String key = normalizeEmail(email);
    byte[] providedBytes = (otp == null ? "" : otp).getBytes(StandardCharsets.UTF_8);
    AtomicBoolean matched = new AtomicBoolean(false);

    otpStorage.compute(
        key,
        (k, existing) -> {
          if (existing == null) {
            return null;
          }
          LocalDateTime now = LocalDateTime.now();
          if (!now.isBefore(existing.expirationTime())) {
            return null;
          }
          byte[] expectedBytes = existing.otp().getBytes(StandardCharsets.UTF_8);
          if (MessageDigest.isEqual(expectedBytes, providedBytes)) {
            matched.set(true);
            return null;
          }
          int nextAttempts = existing.attempts() + 1;
          if (nextAttempts >= MAX_ATTEMPTS) {
            return null;
          }
          return new OtpData(
              existing.otp(), existing.expirationTime(), existing.generationTime(), nextAttempts);
        });

    if (!matched.get()) {
      return false;
    }

    User user = userRepository.findByEmail(email);

    if (user != null && user.isBanned()) {
      throw new DomainException(HttpStatus.FORBIDDEN, "ACCOUNT_BANNED", "Your account is banned");
    }

    if (user != null && !user.isActive() && !user.isBanned()) {
      activateUserAccount(email);
    }

    return true;
  }

  /**
   * Checks if OTP resend cooldown has elapsed.
   *
   * @param email user email address
   * @return true if allowed to resend, false otherwise
   */
  public boolean isResendAllowed(String email) {
    OtpData existingOtpData = otpStorage.get(normalizeEmail(email));
    if (existingOtpData == null) {
      return true;
    }
    LocalDateTime allowedResendTime =
        existingOtpData.generationTime().plusMinutes(resendCooldownMinutes);

    return !LocalDateTime.now().isBefore(allowedResendTime);
  }

  /**
   * Gets remaining resend cooldown time in seconds.
   *
   * @param email user email address
   * @return remaining seconds
   */
  public long getRemainingCooldownSeconds(String email) {
    OtpData existingOtpData = otpStorage.get(normalizeEmail(email));
    if (existingOtpData == null) {
      return 0;
    }
    LocalDateTime lastSentTime = existingOtpData.generationTime();
    LocalDateTime allowedResendTime = lastSentTime.plusMinutes(resendCooldownMinutes);
    LocalDateTime now = LocalDateTime.now();

    if (now.isBefore(allowedResendTime)) {
      return Duration.between(now, allowedResendTime).getSeconds();
    }
    return 0;
  }

  /** Periodically evicts expired OTP entries to prevent unbounded memory growth. */
  @Scheduled(fixedDelay = 300_000)
  public void evictExpiredOtps() {
    LocalDateTime now = LocalDateTime.now();
    otpStorage.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expirationTime()));
  }

  /**
   * Publishes an OTP email event to RabbitMQ for asynchronous delivery.
   *
   * @param email user email address
   * @param otp generated OTP code
   */
  public void sendOtpEmail(String email, String otp) {
    try {
      Map<String, Object> payload =
          Map.of(
              "email", email,
              "otp", otp,
              "expirationMinutes", otpExpirationMinutes);
      rabbitTemplate.convertAndSend(
          RabbitMQConfig.EXCHANGE, RabbitMQConfig.OTP_ROUTING_KEY, payload);
      log.info("Published OTP email send event to RabbitMQ for {}", email);
    } catch (Exception e) {
      log.error("Failed to publish OTP email event for {} due to error: {}", email, e.getMessage());
    }
  }

  private void activateUserAccount(String email) {
    User user = userRepository.findByEmail(email);
    if (user != null) {
      user.setActive(true);
      userRepository.save(user);
    }
  }

  private static String normalizeEmail(String email) {
    return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
  }

  private record OtpData(
      String otp, LocalDateTime expirationTime, LocalDateTime generationTime, int attempts) {}
}

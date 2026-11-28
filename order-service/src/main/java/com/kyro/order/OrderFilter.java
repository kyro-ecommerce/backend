package com.kyro.order;

import com.kyro.enums.OrderStatus;
import com.kyro.enums.PaymentMethod;
import com.kyro.enums.PaymentStatus;
import java.time.LocalDate;
import java.util.Locale;

public record OrderFilter(
    Long userId,
    String search,
    OrderStatus status,
    PaymentMethod paymentMethod,
    PaymentStatus paymentStatus,
    LocalDate startDate,
    LocalDate endDate,
    Integer minTotal,
    Integer maxTotal) {

  public static OrderFilter from(
      Long userId,
      String search,
      String status,
      String paymentMethod,
      String paymentStatus,
      LocalDate startDate,
      LocalDate endDate,
      Integer minTotal,
      Integer maxTotal) {
    String normalizedSearch = (search != null && !search.trim().isEmpty()) ? search.trim() : null;
    return new OrderFilter(
        userId,
        normalizedSearch,
        parse(OrderStatus.class, status),
        parse(PaymentMethod.class, paymentMethod),
        parse(PaymentStatus.class, paymentStatus),
        startDate,
        endDate,
        minTotal,
        maxTotal);
  }

  private static <E extends Enum<E>> E parse(Class<E> type, String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Unsupported " + type.getSimpleName() + ": " + value);
    }
  }
}

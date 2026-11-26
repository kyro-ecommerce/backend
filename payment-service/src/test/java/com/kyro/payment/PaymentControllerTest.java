package com.kyro.payment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.kyro.exceptions.DomainException;
import com.kyro.payment.client.OrderClient;
import com.kyro.payment.dto.PaymentDTO;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class PaymentControllerTest {
  private final PaymentService service = mock(PaymentService.class);
  private final PaymentRepository repository = mock(PaymentRepository.class);
  private final OrderClient orders = mock(OrderClient.class);
  private final PaymentController controller = new PaymentController(service, orders, repository);

  @ParameterizedTest
  @ValueSource(strings = {"ADMIN", "ROLE_ADMIN", " USER, ROLE_ADMIN ", " ADMIN , USER"})
  void adminAliasesCanReadBothReviewEndpointsAndOtherUsersPayments(String roles) {
    Payment payment = new Payment();
    payment.setId(7L);
    payment.setOrderId(42L);
    when(repository.findByReviewRequiredTrue()).thenReturn(List.of(payment));
    when(repository.findById(7L)).thenReturn(Optional.of(payment));
    when(repository.findByOrderId(42L)).thenReturn(Optional.of(payment));
    when(service.toDto(payment)).thenReturn(new PaymentDTO(payment, null));
    when(service.transactions(42L)).thenReturn(List.of());
    when(orders.getOrderById(42L))
        .thenReturn(
            new OrderClient.OrderResponse(
                42L, "test", 99L, 100L, "COMPLETED", "VNPAY", "CONFIRMED", null));
    assertDoesNotThrow(() -> controller.reviews(roles));
    assertDoesNotThrow(() -> controller.review(roles, 7L));
    assertDoesNotThrow(() -> controller.getPaymentByOrderId(1L, roles, 42L));
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "USER", "ROLE_USER", "NOT_ADMIN", "ADMINISTRATOR"})
  void nonAdminsCannotReadReviews(String roles) {
    assertEquals(
        403,
        assertThrows(DomainException.class, () -> controller.reviews(roles)).getStatus().value());
    assertEquals(
        403,
        assertThrows(DomainException.class, () -> controller.review(roles, 7L))
            .getStatus()
            .value());
    verifyNoInteractions(repository, service);
  }
}

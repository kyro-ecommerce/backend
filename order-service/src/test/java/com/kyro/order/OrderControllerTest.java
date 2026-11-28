package com.kyro.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.kyro.enums.OrderStatus;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

class OrderControllerTest {

  @Test
  void orderFilterNormalizesBlankAndWhitespaceSearchToNull() {
    OrderFilter blankFilter = OrderFilter.from(1L, "   ", null, null, null, null, null, null, null);
    assertNull(blankFilter.search());

    OrderFilter emptyFilter = OrderFilter.from(1L, "", null, null, null, null, null, null, null);
    assertNull(emptyFilter.search());

    OrderFilter trimmedFilter =
        OrderFilter.from(1L, "  KYR-123  ", null, null, null, null, null, null, null);
    assertEquals("KYR-123", trimmedFilter.search());
  }

  @Test
  void escapeLikeEscapesWildcardsAndBackslash() {
    assertEquals("100\\%\\_test\\\\a", OrderService.escapeLike("100%_test\\a"));
  }

  @Test
  void getUserOrdersForwardsUserIdSearchAndStatusToService() {
    AtomicReference<OrderFilter> capturedRef = new AtomicReference<>();
    OrderService orderService =
        new OrderService(null, null, null, null, null, null) {
          @Override
          public Page<Order> findOrders(OrderFilter filter, Pageable pageable) {
            capturedRef.set(filter);
            return Page.empty(pageable);
          }
        };

    OrderController controller = new OrderController(orderService);
    controller.getUserOrders(
        42L, "  Playstation  ", "CANCELLED", null, null, null, null, null, null, 0, 10, null);

    OrderFilter captured = capturedRef.get();
    assertNotNull(captured);
    assertEquals(42L, captured.userId());
    assertEquals("Playstation", captured.search());
    assertEquals(OrderStatus.CANCELLED, captured.status());
  }
}

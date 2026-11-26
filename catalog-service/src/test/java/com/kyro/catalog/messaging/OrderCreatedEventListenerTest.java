package com.kyro.catalog.messaging;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.kyro.catalog.StockReservationService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OrderCreatedEventListenerTest {
  @Test
  void rejectsMissingOrderIdBeforeChangingStock() {
    var listener = new OrderCreatedEventListener(new StockReservationService(null, null, null));
    assertThrows(
        RuntimeException.class, () -> listener.handleOrderCreated(Map.of("items", List.of())));
  }
}

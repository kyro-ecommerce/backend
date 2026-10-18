package com.kyro.cart.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StockReservedEventListenerTest {
  @Test
  void extractsOnlyPurchasedCartItemsFromStockEvent() {
    assertEquals(
        Map.of(11L, 2),
        StockReservedEventListener.extractQuantities(
            List.of(Map.of("cartItemId", 11L, "quantity", 2))));
  }
}

package com.kyro.order.messaging;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import com.kyro.order.event.OrderCancelledEvent;
import com.kyro.reliability.Outbox;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderCancelledEventPublisherTest {
  @Test
  void databaseFailureMustAbortCancellation() {
    var outbox = mock(Outbox.class);
    var event = new OrderCancelledEvent(42L, 10L, List.of(new OrderCancelledEvent.Item(3L, 2)));
    when(outbox.append("order-exchange", "order.cancelled", 42L, event))
        .thenThrow(new IllegalStateException("DB unavailable"));
    assertThrows(
        IllegalStateException.class, () -> new OrderCancelledEventPublisher(outbox).publish(event));
  }
}

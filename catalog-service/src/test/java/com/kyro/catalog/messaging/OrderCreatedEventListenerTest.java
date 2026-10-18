package com.kyro.catalog.messaging;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kyro.catalog.ProductService;
import com.kyro.catalog.config.RabbitMQConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.QueryTimeoutException;

class OrderCreatedEventListenerTest {

  private ProductService productService;
  private RabbitTemplate rabbitTemplate;
  private OrderCreatedEventListener listener;

  @BeforeEach
  void setUp() {
    productService = mock(ProductService.class);
    rabbitTemplate = mock(RabbitTemplate.class);
    listener = new OrderCreatedEventListener(productService, rabbitTemplate);
  }

  @Test
  void successfulReservationPublishesStockReserved() {
    Map<String, Object> event =
        Map.of(
            "orderId", 123L,
            "userId", 456L,
            "items", List.of(Map.of("variantId", 1L, "quantity", 2)));

    listener.handleOrderCreated(event);

    verify(productService).reserveStock(eq(123L), anyList());
    verify(rabbitTemplate)
        .convertAndSend(
            eq(RabbitMQConfig.ORDER_EXCHANGE),
            eq("stock.reserved"),
            argThat((Map<String, Object> map) -> Boolean.TRUE.equals(map.get("success"))));
  }

  @Test
  void businessFailurePublishesStockFailedCompensation() {
    Map<String, Object> event =
        Map.of(
            "orderId", 123L,
            "userId", 456L,
            "items", List.of(Map.of("variantId", 1L, "quantity", 2)));

    doThrow(new IllegalStateException("Insufficient stock"))
        .when(productService)
        .reserveStock(eq(123L), anyList());

    listener.handleOrderCreated(event);

    verify(rabbitTemplate)
        .convertAndSend(
            eq(RabbitMQConfig.ORDER_EXCHANGE),
            eq("stock.failed"),
            argThat((Map<String, Object> map) -> Boolean.FALSE.equals(map.get("success"))));
  }

  @Test
  void poisonPillNullOrderIdThrowsAmqpRejectAndDontRequeueException() {
    Map<String, Object> event = Map.of("userId", 456L);

    assertThrows(AmqpRejectAndDontRequeueException.class, () -> listener.handleOrderCreated(event));

    verify(productService, never()).reserveStock(any(), any());
    verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
  }

  @Test
  void poisonPillEmptyItemsThrowsAmqpRejectAndDontRequeueException() {
    Map<String, Object> event = Map.of("orderId", 123L, "items", List.of());

    assertThrows(AmqpRejectAndDontRequeueException.class, () -> listener.handleOrderCreated(event));

    verify(productService, never()).reserveStock(any(), any());
    verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
  }

  @Test
  void transientInfrastructureFailureRethrowsExceptionForRetry() {
    Map<String, Object> event =
        Map.of(
            "orderId", 123L,
            "userId", 456L,
            "items", List.of(Map.of("variantId", 1L, "quantity", 2)));

    doThrow(new QueryTimeoutException("DB connection timeout"))
        .when(productService)
        .reserveStock(eq(123L), anyList());

    assertThrows(QueryTimeoutException.class, () -> listener.handleOrderCreated(event));

    // Must NOT publish stock.failed for transient DB errors!
    verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
  }
}

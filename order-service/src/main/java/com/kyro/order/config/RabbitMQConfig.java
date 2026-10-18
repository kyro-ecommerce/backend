package com.kyro.order.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Configuration class for Order Service RabbitMQ queues and exchange. */
@Configuration
public class RabbitMQConfig {

  public static final String ORDER_EXCHANGE = "order-exchange";
  public static final String PAYMENT_EXCHANGE = "payment-exchange";
  public static final String NOTIFICATION_EXCHANGE = "notification-exchange";
  public static final String DLX_EXCHANGE = "kyro.dlx";

  public static final String ORDER_CREATED_ROUTING_KEY = "order.created";
  public static final String ORDER_DELIVERED_ROUTING_KEY = "order.delivered";
  public static final String STOCK_RESERVED_ROUTING_KEY = "stock.reserved";
  public static final String STOCK_FAILED_ROUTING_KEY = "stock.failed";

  public static final String ORDER_STOCK_RESULT_QUEUE = "order-stock-result-queue";
  public static final String ORDER_STOCK_RESULT_DLQ = "order-stock-result-queue.dlq";
  public static final String ORDER_SAGA_QUEUE = ORDER_STOCK_RESULT_QUEUE;

  public static final String PAYMENT_STATUS_QUEUE = "order-payment-status-queue";
  public static final String PAYMENT_STATUS_DLQ = "order-payment-status-queue.dlq";
  public static final String PAYMENT_STATUS_UPDATED_ROUTING_KEY = "payment.status.updated";
  public static final String ORDER_NOTIFICATION_ROUTING_KEY = "notification.order";

  @Bean
  public TopicExchange orderExchange() {
    return new TopicExchange(ORDER_EXCHANGE);
  }

  @Bean
  public TopicExchange paymentExchange() {
    return new TopicExchange(PAYMENT_EXCHANGE);
  }

  @Bean
  public DirectExchange deadLetterExchange() {
    return new DirectExchange(DLX_EXCHANGE);
  }

  @Bean
  public Queue orderStockResultQueue() {
    return QueueBuilder.durable(ORDER_STOCK_RESULT_QUEUE)
        .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
        .withArgument("x-dead-letter-routing-key", ORDER_STOCK_RESULT_DLQ)
        .build();
  }

  @Bean
  public Queue orderStockResultDlq() {
    return QueueBuilder.durable(ORDER_STOCK_RESULT_DLQ).build();
  }

  @Bean
  public Binding stockReservedBinding() {
    return BindingBuilder.bind(orderStockResultQueue())
        .to(orderExchange())
        .with(STOCK_RESERVED_ROUTING_KEY);
  }

  @Bean
  public Binding stockFailedBinding() {
    return BindingBuilder.bind(orderStockResultQueue())
        .to(orderExchange())
        .with(STOCK_FAILED_ROUTING_KEY);
  }

  @Bean
  public Binding orderStockResultDlqBinding() {
    return BindingBuilder.bind(orderStockResultDlq())
        .to(deadLetterExchange())
        .with(ORDER_STOCK_RESULT_DLQ);
  }

  @Bean
  public Queue paymentStatusQueue() {
    return QueueBuilder.durable(PAYMENT_STATUS_QUEUE)
        .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
        .withArgument("x-dead-letter-routing-key", PAYMENT_STATUS_DLQ)
        .build();
  }

  @Bean
  public Queue paymentStatusDlq() {
    return QueueBuilder.durable(PAYMENT_STATUS_DLQ).build();
  }

  @Bean
  public Binding paymentStatusBinding() {
    return BindingBuilder.bind(paymentStatusQueue())
        .to(paymentExchange())
        .with(PAYMENT_STATUS_UPDATED_ROUTING_KEY);
  }

  @Bean
  public Binding paymentStatusDlqBinding() {
    return BindingBuilder.bind(paymentStatusDlq())
        .to(deadLetterExchange())
        .with(PAYMENT_STATUS_DLQ);
  }

  @Bean
  public MessageConverter jsonMessageConverter() {
    return new Jackson2JsonMessageConverter();
  }
}

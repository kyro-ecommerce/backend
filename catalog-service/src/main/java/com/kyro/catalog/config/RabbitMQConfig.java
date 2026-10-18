package com.kyro.catalog.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

  public static final String ORDER_EXCHANGE = "order-exchange";
  public static final String DLX_EXCHANGE = "kyro.dlx";

  public static final String ORDER_CREATED_ROUTING_KEY = "order.created";
  public static final String ORDER_DELIVERED_ROUTING_KEY = "order.delivered";

  public static final String CATALOG_ORDER_QUEUE = "catalog-order-created-queue";
  public static final String CATALOG_ORDER_DLQ = "catalog-order-created-queue.dlq";

  public static final String CATALOG_ORDER_DELIVERED_QUEUE = "catalog-order-delivered-queue";
  public static final String CATALOG_ORDER_DELIVERED_DLQ = "catalog-order-delivered-queue.dlq";

  @Bean
  public TopicExchange orderExchange() {
    return new TopicExchange(ORDER_EXCHANGE);
  }

  @Bean
  public DirectExchange deadLetterExchange() {
    return new DirectExchange(DLX_EXCHANGE);
  }

  @Bean
  public Queue catalogOrderQueue() {
    return QueueBuilder.durable(CATALOG_ORDER_QUEUE)
        .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
        .withArgument("x-dead-letter-routing-key", CATALOG_ORDER_DLQ)
        .build();
  }

  @Bean
  public Queue catalogOrderDlq() {
    return QueueBuilder.durable(CATALOG_ORDER_DLQ).build();
  }

  @Bean
  public Binding catalogOrderBinding() {
    return BindingBuilder.bind(catalogOrderQueue())
        .to(orderExchange())
        .with(ORDER_CREATED_ROUTING_KEY);
  }

  @Bean
  public Binding catalogOrderDlqBinding() {
    return BindingBuilder.bind(catalogOrderDlq()).to(deadLetterExchange()).with(CATALOG_ORDER_DLQ);
  }

  @Bean
  public Queue catalogOrderDeliveredQueue() {
    return QueueBuilder.durable(CATALOG_ORDER_DELIVERED_QUEUE)
        .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
        .withArgument("x-dead-letter-routing-key", CATALOG_ORDER_DELIVERED_DLQ)
        .build();
  }

  @Bean
  public Queue catalogOrderDeliveredDlq() {
    return QueueBuilder.durable(CATALOG_ORDER_DELIVERED_DLQ).build();
  }

  @Bean
  public Binding catalogOrderDeliveredBinding() {
    return BindingBuilder.bind(catalogOrderDeliveredQueue())
        .to(orderExchange())
        .with(ORDER_DELIVERED_ROUTING_KEY);
  }

  @Bean
  public Binding catalogOrderDeliveredDlqBinding() {
    return BindingBuilder.bind(catalogOrderDeliveredDlq())
        .to(deadLetterExchange())
        .with(CATALOG_ORDER_DELIVERED_DLQ);
  }

  @Bean
  public MessageConverter jsonMessageConverter() {
    return new Jackson2JsonMessageConverter();
  }
}

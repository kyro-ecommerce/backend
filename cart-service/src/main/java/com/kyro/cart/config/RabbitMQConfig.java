package com.kyro.cart.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

  public static final String ORDER_EXCHANGE = "order-exchange";
  public static final String DLX_EXCHANGE = "kyro.dlx";
  public static final String STOCK_RESERVED_ROUTING_KEY = "stock.reserved";

  public static final String CART_STOCK_RESERVED_QUEUE = "cart-stock-reserved-queue";
  public static final String CART_STOCK_RESERVED_DLQ = "cart-stock-reserved-queue.dlq";
  public static final String CART_CLEAR_QUEUE = CART_STOCK_RESERVED_QUEUE;

  @Bean
  public TopicExchange orderExchange() {
    return new TopicExchange(ORDER_EXCHANGE);
  }

  @Bean
  public DirectExchange deadLetterExchange() {
    return new DirectExchange(DLX_EXCHANGE);
  }

  @Bean
  public Queue cartStockReservedQueue() {
    return QueueBuilder.durable(CART_STOCK_RESERVED_QUEUE)
        .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
        .withArgument("x-dead-letter-routing-key", CART_STOCK_RESERVED_DLQ)
        .build();
  }

  @Bean
  public Queue cartStockReservedDlq() {
    return QueueBuilder.durable(CART_STOCK_RESERVED_DLQ).build();
  }

  @Bean
  public Binding cartStockReservedBinding() {
    return BindingBuilder.bind(cartStockReservedQueue())
        .to(orderExchange())
        .with(STOCK_RESERVED_ROUTING_KEY);
  }

  @Bean
  public Binding cartStockReservedDlqBinding() {
    return BindingBuilder.bind(cartStockReservedDlq())
        .to(deadLetterExchange())
        .with(CART_STOCK_RESERVED_DLQ);
  }

  @Bean
  public MessageConverter jsonMessageConverter() {
    return new Jackson2JsonMessageConverter();
  }
}

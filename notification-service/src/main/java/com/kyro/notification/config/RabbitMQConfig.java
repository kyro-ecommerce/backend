package com.kyro.notification.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Configuration class for RabbitMQ queues, exchanges, and message converters. */
@Configuration
public class RabbitMQConfig {

  public static final String EXCHANGE = "notification-exchange";
  public static final String DLX_EXCHANGE = "kyro.dlx";

  public static final String OTP_QUEUE = "notification-otp-queue";
  public static final String OTP_DLQ = "notification-otp-queue.dlq";
  public static final String OTP_ROUTING_KEY = "notification.otp";

  public static final String ORDER_QUEUE = "notification-order-confirmed-queue";
  public static final String ORDER_DLQ = "notification-order-confirmed-queue.dlq";
  public static final String ORDER_ROUTING_KEY = "notification.order";

  @Bean
  public TopicExchange exchange() {
    return new TopicExchange(EXCHANGE);
  }

  @Bean
  public DirectExchange deadLetterExchange() {
    return new DirectExchange(DLX_EXCHANGE);
  }

  @Bean
  public Queue otpQueue() {
    return QueueBuilder.durable(OTP_QUEUE)
        .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
        .withArgument("x-dead-letter-routing-key", OTP_DLQ)
        .build();
  }

  @Bean
  public Queue otpDlq() {
    return QueueBuilder.durable(OTP_DLQ).build();
  }

  @Bean
  public Binding otpBinding() {
    return BindingBuilder.bind(otpQueue()).to(exchange()).with(OTP_ROUTING_KEY);
  }

  @Bean
  public Binding otpDlqBinding() {
    return BindingBuilder.bind(otpDlq()).to(deadLetterExchange()).with(OTP_DLQ);
  }

  @Bean
  public Queue orderQueue() {
    return QueueBuilder.durable(ORDER_QUEUE)
        .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
        .withArgument("x-dead-letter-routing-key", ORDER_DLQ)
        .build();
  }

  @Bean
  public Queue orderDlq() {
    return QueueBuilder.durable(ORDER_DLQ).build();
  }

  @Bean
  public Binding orderBinding() {
    return BindingBuilder.bind(orderQueue()).to(exchange()).with(ORDER_ROUTING_KEY);
  }

  @Bean
  public Binding orderDlqBinding() {
    return BindingBuilder.bind(orderDlq()).to(deadLetterExchange()).with(ORDER_DLQ);
  }

  @Bean
  public MessageConverter jsonMessageConverter() {
    return new Jackson2JsonMessageConverter();
  }
}

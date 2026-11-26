package com.kyro.reliability;

import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IntegrationTopology {
  @Bean
  public Declarables aiIntegrationTopology() {
    var objects = new ArrayList<Declarable>();
    var product = new TopicExchange("product.events");
    var order = new TopicExchange("order-exchange");
    var dlx = new DirectExchange("kyro.dlx");
    var queue =
        QueueBuilder.durable("ai.integration.events.v2")
            .deadLetterExchange("kyro.dlx")
            .deadLetterRoutingKey("ai.integration.events.v2.dlq")
            .build();
    var dlq = new Queue("ai.integration.events.v2.dlq");
    objects.addAll(
        List.of(
            product, order, dlx, queue, dlq, BindingBuilder.bind(dlq).to(dlx).with(dlq.getName())));
    for (String route :
        List.of(
            "product.created",
            "product.updated",
            "product.deleted",
            "stock.changed",
            "user.product.impression",
            "user.product.clicked",
            "user.product.viewed",
            "user.cart.added")) objects.add(BindingBuilder.bind(queue).to(product).with(route));
    for (String route : List.of("order.created", "order.cancelled", "order.delivered"))
      objects.add(BindingBuilder.bind(queue).to(order).with(route));
    addQueue(objects, order, dlx, "catalog-order-created-queue", "order.created");
    addQueue(objects, order, dlx, "payment-order-cancelled-queue", "order.cancelled");
    addQueue(objects, order, dlx, "catalog-order-delivered-queue", "order.delivered");
    addQueue(objects, order, dlx, "catalog-stock-release-queue", "stock.release");
    addQueue(objects, order, dlx, "cart-order-confirmed-queue", "order.confirmed");
    addQueue(objects, order, dlx, "order-stock-result-queue", "stock.reserved", "stock.failed");
    var payment = new TopicExchange("payment-exchange");
    objects.add(payment);
    addQueue(objects, payment, dlx, "order-payment-status-queue", "payment.status.updated");
    return new Declarables(objects);
  }

  private static void addQueue(
      List<Declarable> objects,
      TopicExchange exchange,
      DirectExchange dlx,
      String name,
      String... keys) {
    var queue =
        QueueBuilder.durable(name)
            .deadLetterExchange("kyro.dlx")
            .deadLetterRoutingKey(name + ".dlq")
            .build();
    var dead = new Queue(name + ".dlq");
    objects.add(queue);
    objects.add(dead);
    objects.add(BindingBuilder.bind(dead).to(dlx).with(dead.getName()));
    for (String key : keys) objects.add(BindingBuilder.bind(queue).to(exchange).with(key));
  }
}

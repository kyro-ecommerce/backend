package com.kyro.catalog.messaging;

import com.kyro.catalog.Product;
import com.kyro.catalog.ProductVariant;
import com.kyro.catalog.dto.ProductDTO;
import com.kyro.reliability.Outbox;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ProductEventPublisher {
  public static final String PRODUCT_EXCHANGE = "product.events";
  private final Outbox outbox;
  private final EntityManager entityManager;

  public ProductEventPublisher(Outbox outbox, EntityManager entityManager) {
    this.outbox = outbox;
    this.entityManager = entityManager;
  }

  public void publishProductCreated(Product product) {
    publish(product, "product.created", "ProductCreated");
  }

  public void publishProductUpdated(Product product) {
    publish(product, "product.updated", "ProductUpdated");
  }

  public void publishProductDeleted(Long id) {
    entityManager.flush();
    outbox.append(
        PRODUCT_EXCHANGE,
        "product.deleted",
        id,
        Map.of(
            "event_type",
            "ProductDeleted",
            "occurred_at",
            Instant.now().toString(),
            "data",
            Map.of("product_id", id, "title", "deleted", "is_active", false)));
  }

  private void publish(Product product, String route, String type) {
    entityManager.flush();
    entityManager.refresh(product);
    outbox.append(
        PRODUCT_EXCHANGE,
        route,
        product.getId(),
        Map.of(
            "event_type",
            type,
            "occurred_at",
            Instant.now().toString(),
            "snapshot",
            new ProductDTO(product)));
  }

  public void publishStock(ProductVariant variant) {
    entityManager.flush();
    outbox.append(
        PRODUCT_EXCHANGE,
        "stock.changed",
        variant.getId(),
        Map.of(
            "productId",
            variant.getProduct().getId(),
            "variantId",
            variant.getId(),
            "stock",
            variant.getStock(),
            "stockVersion",
            variant.getStockVersion()));
  }
}

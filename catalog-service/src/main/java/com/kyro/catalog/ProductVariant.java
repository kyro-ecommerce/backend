package com.kyro.catalog;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;

@Entity
@Table(
    name = "product_variant",
    uniqueConstraints = {
      @UniqueConstraint(columnNames = "sku"),
      @UniqueConstraint(columnNames = {"product_id", "variant_name"})
    })
public class ProductVariant {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "product_id", nullable = false)
  @JsonIgnore
  private Product product;

  @NotBlank
  @Column(nullable = false, length = 100)
  private String sku;

  @NotBlank
  @Column(name = "variant_name", nullable = false)
  private String variantName;

  @PositiveOrZero
  @Column(nullable = false)
  private long price;

  @PositiveOrZero
  @Column(nullable = false)
  private int stock;

  @Version
  @Column(name = "stock_version", nullable = false)
  private long stockVersion;

  public long getStockVersion() {
    return stockVersion;
  }

  @Column(nullable = false)
  private boolean active = true;

  public Long getId() {
    return id;
  }

  public void setId(Long value) {
    id = value;
  }

  public Product getProduct() {
    return product;
  }

  public void setProduct(Product value) {
    product = value;
  }

  public String getSku() {
    return sku;
  }

  public void setSku(String value) {
    sku = value;
  }

  public String getVariantName() {
    return variantName;
  }

  public void setVariantName(String value) {
    variantName = value;
  }

  public long getPrice() {
    return price;
  }

  public void setPrice(long value) {
    price = value;
  }

  public int getStock() {
    return stock;
  }

  public void setStock(int value) {
    stock = value;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean value) {
    active = value;
  }
}

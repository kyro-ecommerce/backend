package com.kyro.order;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Entity representing an item inside an order. Stores product details as denormalized fields. */
@Entity
@Table(name = "order_item")
public class OrderItem {

  @Column(name = "cart_item_id")
  private Long cartItemId;

  public Long getCartItemId() {
    return cartItemId;
  }

  public void setCartItemId(Long value) {
    cartItemId = value;
  }

  public OrderItem() {}

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @JsonIgnore
  @ManyToOne
  @JoinColumn(name = "order_id", nullable = false)
  private Order order;

  @Column(name = "product_id", nullable = false)
  private Long productId;

  @Column(name = "variant_id", nullable = false)
  private Long variantId;

  @Column(name = "sku", nullable = false)
  private String sku;

  @Column(name = "product_name")
  private String productName;

  @Column(name = "product_image_url")
  private String productImageUrl;

  @Column(name = "quantity")
  private int quantity;

  @Column(name = "price")
  private long price;

  @Column(name = "variant_name")
  private String variantName;

  @Column(name = "discounted_price")
  private Long discountedPrice;

  @Column(name = "delivery_date")
  private LocalDateTime deliveryDate;

  @Column(name = "discount_percent")
  private Integer discountPercent;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public Order getOrder() {
    return order;
  }

  public void setOrder(Order order) {
    this.order = order;
  }

  public Long getProductId() {
    return productId;
  }

  public void setProductId(Long productId) {
    this.productId = productId;
  }

  public String getProductName() {
    return productName;
  }

  public void setProductName(String productName) {
    this.productName = productName;
  }

  public String getProductImageUrl() {
    return productImageUrl;
  }

  public void setProductImageUrl(String productImageUrl) {
    this.productImageUrl = productImageUrl;
  }

  public int getQuantity() {
    return quantity;
  }

  public void setQuantity(int quantity) {
    this.quantity = quantity;
  }

  public long getPrice() {
    return price;
  }

  public void setPrice(long price) {
    this.price = price;
  }

  public String getSize() {
    return variantName;
  }

  public void setSize(String size) {
    this.variantName = size;
  }

  public Long getDiscountedPrice() {
    return discountedPrice;
  }

  public void setDiscountedPrice(Long discountedPrice) {
    this.discountedPrice = discountedPrice;
  }

  public Long getVariantId() {
    return variantId;
  }

  public void setVariantId(Long value) {
    variantId = value;
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

  public LocalDateTime getDeliveryDate() {
    return deliveryDate;
  }

  public void setDeliveryDate(LocalDateTime deliveryDate) {
    this.deliveryDate = deliveryDate;
  }

  public Integer getDiscountPercent() {
    return discountPercent;
  }

  public void setDiscountPercent(Integer discountPercent) {
    this.discountPercent = discountPercent;
  }

  public Long getUserId() {
    return order != null ? order.getUserId() : null;
  }
}

package com.kyro.order;

import com.kyro.enums.OrderStatus;
import com.kyro.enums.PaymentMethod;
import com.kyro.enums.PaymentStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.UUID;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.DynamicInsert;

@Entity
@DynamicInsert
@Table(name = "orders")
public class Order {

  public Order() {}

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "order_code", nullable = false, unique = true, updatable = false, length = 16)
  private String orderCode;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(name = "user_email")
  private String userEmail;

  @Column(name = "order_date")
  private LocalDateTime orderDate;

  @Column(name = "original_price", precision = 19, scale = 2)
  private long originalPrice;

  @Enumerated(EnumType.STRING)
  @Column(name = "order_status")
  private OrderStatus orderStatus;

  @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
  private Collection<OrderItem> orderItems = new HashSet<>();

  @ManyToOne(cascade = CascadeType.PERSIST)
  @JoinColumn(name = "order_address_id")
  private OrderAddress shippingAddress;

  @Column(name = "delivery_date")
  private LocalDateTime deliveryDate;

  @Column(name = "total_discounted_price")
  private Long totalDiscountedPrice;

  @Column(name = "discount")
  private long discount;

  @Column(name = "total_items")
  private int totalItems;

  @Enumerated(EnumType.STRING)
  @Column(name = "payment_method")
  @ColumnDefault("'COD'")
  private PaymentMethod paymentMethod;

  @Column(name = "payment_status")
  @Enumerated(EnumType.STRING)
  private PaymentStatus paymentStatus;

  @Column(name = "stock_reserved", nullable = false)
  private boolean stockReserved;

  @Column(name = "expires_at")
  private Instant expiresAt;

  @Column(name = "created_at")
  private LocalDateTime createdAt;

  @Column(name = "updated_at")
  private LocalDateTime updatedAt;

  @Column(name = "idempotency_key", length = 128)
  private String idempotencyKey;

  @PrePersist
  void prePersist() {
    assignOrderCode();
    if (orderDate == null) {
      orderDate = LocalDateTime.now();
    }
    createdAt = LocalDateTime.now();
    updatedAt = LocalDateTime.now();
  }

  void assignOrderCode() {
    if (orderCode == null) {
      orderCode =
          "KYR-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = LocalDateTime.now();
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getOrderCode() {
    return orderCode;
  }

  public Long getUserId() {
    return userId;
  }

  public void setUserId(Long userId) {
    this.userId = userId;
  }

  public String getUserEmail() {
    return userEmail;
  }

  public void setUserEmail(String userEmail) {
    this.userEmail = userEmail;
  }

  public LocalDateTime getOrderDate() {
    return orderDate;
  }

  public void setOrderDate(LocalDateTime orderDate) {
    this.orderDate = orderDate;
  }

  public long getOriginalPrice() {
    return originalPrice;
  }

  public void setOriginalPrice(long originalPrice) {
    this.originalPrice = originalPrice;
  }

  public OrderStatus getOrderStatus() {
    return orderStatus;
  }

  public void setOrderStatus(OrderStatus orderStatus) {
    this.orderStatus = orderStatus;
  }

  public Collection<OrderItem> getOrderItems() {
    return orderItems;
  }

  public void setOrderItems(Collection<OrderItem> orderItems) {
    this.orderItems = orderItems;
  }

  public OrderAddress getShippingAddress() {
    return shippingAddress;
  }

  public void setShippingAddress(OrderAddress shippingAddress) {
    this.shippingAddress = shippingAddress;
  }

  public LocalDateTime getDeliveryDate() {
    return deliveryDate;
  }

  public void setDeliveryDate(LocalDateTime deliveryDate) {
    this.deliveryDate = deliveryDate;
  }

  public Long getTotalDiscountedPrice() {
    return totalDiscountedPrice;
  }

  public void setTotalDiscountedPrice(Long totalDiscountedPrice) {
    this.totalDiscountedPrice = totalDiscountedPrice;
  }

  public long getDiscount() {
    return discount;
  }

  public void setDiscount(long discount) {
    this.discount = discount;
  }

  public int getTotalItems() {
    return totalItems;
  }

  public void setTotalItems(int totalItems) {
    this.totalItems = totalItems;
  }

  public PaymentMethod getPaymentMethod() {
    return paymentMethod;
  }

  public void setPaymentMethod(PaymentMethod paymentMethod) {
    this.paymentMethod = paymentMethod;
  }

  public PaymentStatus getPaymentStatus() {
    return paymentStatus;
  }

  public void setPaymentStatus(PaymentStatus paymentStatus) {
    this.paymentStatus = paymentStatus;
  }

  public boolean isStockReserved() {
    return stockReserved;
  }

  public void setStockReserved(boolean stockReserved) {
    this.stockReserved = stockReserved;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(Instant expiresAt) {
    this.expiresAt = expiresAt;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(LocalDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public void setIdempotencyKey(String idempotencyKey) {
    this.idempotencyKey = idempotencyKey;
  }
}

package com.kyro.payment;

import com.kyro.enums.PaymentMethod;
import com.kyro.enums.PaymentStatus;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "payments")
@Getter
@Setter
public class Payment {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "order_id", nullable = false, unique = true)
  private Long orderId;

  @Enumerated(EnumType.STRING)
  private PaymentMethod paymentMethod;

  @Enumerated(EnumType.STRING)
  private PaymentStatus paymentStatus;

  private LocalDateTime paymentDate;
  private long totalAmount;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
  private Long winningTransactionId;
  private boolean reviewRequired;
  private String reviewReason;

  @PrePersist
  void create() {
    createdAt = LocalDateTime.now();
    updatedAt = createdAt;
  }

  @PreUpdate
  void update() {
    updatedAt = LocalDateTime.now();
  }
}

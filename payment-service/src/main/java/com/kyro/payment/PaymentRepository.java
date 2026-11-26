package com.kyro.payment;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from Payment p where p.orderId=:orderId")
  Optional<Payment> findByOrderIdForUpdate(Long orderId);

  List<Payment> findByReviewRequiredTrue();

  Optional<Payment> findByOrderId(Long orderId);
}

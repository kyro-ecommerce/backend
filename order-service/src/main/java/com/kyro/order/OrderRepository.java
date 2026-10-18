package com.kyro.order;

import com.kyro.enums.OrderStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repository interface for managing Order database operations in order-service. */
public interface OrderRepository
    extends JpaRepository<Order, Long>, JpaSpecificationExecutor<Order> {

  List<Order> findByUserId(Long userId);

  Optional<Order> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT o FROM Order o WHERE o.id = :id")
  Optional<Order> findByIdForUpdate(@Param("id") Long id);

  @Query(
      value =
          "SELECT id FROM orders WHERE order_status = 'PENDING'"
              + " AND payment_method = 'VNPAY'"
              + " AND payment_status IS DISTINCT FROM 'COMPLETED'"
              + " AND expires_at <= :cutoff ORDER BY expires_at, id",
      nativeQuery = true)
  List<Long> findExpiredVnpayOrderIds(@Param("cutoff") Instant cutoff, Pageable pageable);

  List<Order> findByOrderDateBetweenAndOrderStatus(
      LocalDateTime startDate, LocalDateTime endDate, OrderStatus status);

  List<Order> findByOrderDateGreaterThanEqualAndOrderStatus(
      LocalDateTime startDate, OrderStatus status);

  List<Order> findByOrderStatus(OrderStatus status);

  List<Order> findByOrderDateBetween(LocalDateTime start, LocalDateTime end);

  @Modifying
  @Query("DELETE FROM Order o WHERE o.userId = :userId")
  void deleteByUserId(@Param("userId") Long userId);

  @Query("SELECT o FROM Order o LEFT JOIN FETCH o.shippingAddress")
  List<Order> findAllWithUser();

  List<Order> findOrderByOrderStatus(OrderStatus status);

  List<Order> findByUserIdAndOrderStatus(Long userId, OrderStatus orderStatus);

  @Query("SELECT o FROM Order o LEFT JOIN FETCH o.shippingAddress ORDER BY o.orderDate DESC")
  List<Order> findAllWithUserOrderByOrderDateDesc();

  List<Order> findAllByOrderByOrderDateDesc();

  @Query(
      "SELECT COUNT(o) FROM Order o WHERE "
          + "(CAST(:startDate AS timestamp) IS NULL OR o.orderDate >= :startDate) "
          + "AND (CAST(:endDate AS timestamp) IS NULL OR o.orderDate <= :endDate) "
          + "AND (:status IS NULL OR o.orderStatus = :status)")
  Long countOrdersByStatusAndDateRange(
      @Param("status") OrderStatus status,
      @Param("startDate") LocalDateTime startDate,
      @Param("endDate") LocalDateTime endDate);

  @Query(
      "SELECT SUM(o.totalDiscountedPrice) FROM Order o WHERE "
          + "(CAST(:startDate AS timestamp) IS NULL OR o.orderDate >= :startDate) "
          + "AND (CAST(:endDate AS timestamp) IS NULL OR o.orderDate <= :endDate) "
          + "AND o.orderStatus = 'DELIVERED'")
  Double sumRevenueByDateRange(
      @Param("startDate") LocalDateTime startDate, @Param("endDate") LocalDateTime endDate);

  @Query(
      "SELECT CAST(FUNCTION('DATE', o.orderDate) AS string), SUM(o.totalDiscountedPrice) FROM Order"
          + " o WHERE (CAST(:startDate AS timestamp) IS NULL OR o.orderDate >= :startDate) AND"
          + " (CAST(:endDate AS timestamp) IS NULL OR o.orderDate <= :endDate) GROUP BY"
          + " FUNCTION('DATE', o.orderDate) ORDER BY FUNCTION('DATE', o.orderDate) ASC")
  List<Object[]> findDailyRevenueByDateRange(
      @Param("startDate") LocalDateTime startDate, @Param("endDate") LocalDateTime endDate);
}

package com.kyro.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcessedStockReservationRepository
    extends JpaRepository<ProcessedStockReservation, Long> {

  @Modifying
  @Query(
      value =
          "INSERT INTO processed_stock_reservation(order_id, created_at) "
              + "VALUES (:orderId, CURRENT_TIMESTAMP) ON CONFLICT (order_id) DO NOTHING",
      nativeQuery = true)
  int markReservedIfAbsent(@Param("orderId") Long orderId);
}

package com.kyro.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kyro.reliability.Outbox;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StockReservationService {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final Outbox outbox;

  public StockReservationService(JdbcTemplate jdbc, ObjectMapper mapper, Outbox outbox) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.outbox = outbox;
  }

  @Transactional
  public void reserve(long orderId, List<Map<String, Object>> items) {
    var quantities = quantities(items);
    int inserted =
        jdbc.update(
            "insert into processed_stock_reservation(order_id,status) values (?,'REJECTED') on"
                + " conflict do nothing",
            orderId);
    var reservation =
        jdbc.queryForMap(
            "select * from processed_stock_reservation where order_id=? for update", orderId);
    boolean reserved = "RESERVED".equals(reservation.get("status"));
    if (inserted != 0) {
      boolean available = true;
      // Lock all variants in one deterministic order before changing any quantity.
      for (var entry : quantities.entrySet()) {
        var variants =
            jdbc.queryForList(
                "select stock,active from product_variant where id=? for update", entry.getKey());
        if (variants.isEmpty()
            || !Boolean.TRUE.equals(variants.getFirst().get("active"))
            || ((Number) variants.getFirst().get("stock")).intValue() < entry.getValue())
          available = false;
      }
      if (available) {
        quantities.forEach((variantId, quantity) -> changeStock(variantId, -quantity));
        jdbc.update(
            "update processed_stock_reservation set status='RESERVED',items=? where order_id=?",
            json(items),
            orderId);
        reserved = true;
      }
    }
    outbox.append(
        "order-exchange",
        reserved ? "stock.reserved" : "stock.failed",
        orderId,
        Map.of(
            "orderId",
            orderId,
            "success",
            reserved,
            "message",
            reserved ? "Stock reserved" : "Reservation rejected or released"));
  }

  @Transactional
  public void release(long orderId, List<Map<String, Object>> legacyItems) {
    jdbc.update(
        "insert into processed_stock_reservation(order_id,status) values (?,'RELEASED') on conflict"
            + " do nothing",
        orderId);
    var reservation =
        jdbc.queryForMap(
            "select * from processed_stock_reservation where order_id=? for update", orderId);
    if ("RESERVED".equals(reservation.get("status"))) {
      List<Map<String, Object>> items = legacyItems;
      if (reservation.get("items") != null) {
        try {
          items = mapper.readValue((String) reservation.get("items"), new TypeReference<>() {});
        } catch (Exception error) {
          throw new IllegalStateException("Invalid stored reservation", error);
        }
      }
      if (items == null || items.isEmpty())
        throw new IllegalStateException("Legacy reservation needs original order lines");
      quantities(items).forEach(this::changeStock);
    }
    jdbc.update(
        "update processed_stock_reservation set status='RELEASED' where order_id=?", orderId);
  }

  private void changeStock(long variantId, int delta) {
    var changed =
        jdbc.queryForMap(
            "update product_variant set stock=stock+?,stock_version=stock_version+1 where id=?"
                + " returning product_id,stock,stock_version",
            delta,
            variantId);
    outbox.append(
        "product.events",
        "stock.changed",
        variantId,
        Map.of(
            "productId",
            changed.get("product_id"),
            "variantId",
            variantId,
            "stock",
            changed.get("stock"),
            "stockVersion",
            changed.get("stock_version")));
  }

  private static SortedMap<Long, Integer> quantities(List<Map<String, Object>> items) {
    if (items == null || items.isEmpty())
      throw new IllegalArgumentException("Order lines required");
    SortedMap<Long, Integer> result = new TreeMap<>();
    for (var item : items) {
      long id = Long.parseLong(String.valueOf(item.get("variantId")));
      int quantity = Integer.parseInt(String.valueOf(item.get("quantity")));
      if (id <= 0 || quantity <= 0)
        throw new IllegalArgumentException("Positive variant and quantity required");
      result.merge(id, quantity, Math::addExact);
    }
    return result;
  }

  private String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (Exception error) {
      throw new IllegalArgumentException(error);
    }
  }
}

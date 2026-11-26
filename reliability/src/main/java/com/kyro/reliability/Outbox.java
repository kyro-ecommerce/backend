package com.kyro.reliability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The caller's business transaction owns the event. A failed insert must roll it back. */
@Component
public class Outbox {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public Outbox(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public long append(String exchange, String routingKey, Object aggregateId, Object event) {
    try {
      Map<String, Object> payload = mapper.convertValue(event, new TypeReference<>() {});
      UUID eventId = UUID.randomUUID();
      Long version =
          jdbc.queryForObject(
              "select nextval(pg_get_serial_sequence('outbox_events','id'))", Long.class);
      payload.put("eventId", eventId.toString());
      payload.put("event_id", eventId.toString());
      payload.put("version", version);
      payload.put("occurredAt", Instant.now().toString());
      jdbc.update(
          "insert into outbox_events(id,event_id,aggregate_id,exchange_name,routing_key,payload)"
              + " values (?,?,?,?,?,?)",
          version,
          eventId,
          String.valueOf(aggregateId),
          exchange,
          routingKey,
          mapper.writeValueAsString(payload));
      return version;
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("Cannot serialize outbox event", error);
    }
  }
}

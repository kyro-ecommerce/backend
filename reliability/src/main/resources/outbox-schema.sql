CREATE TABLE outbox_events (
  id BIGSERIAL PRIMARY KEY,
  event_id UUID NOT NULL UNIQUE,
  aggregate_id VARCHAR(100) NOT NULL,
  exchange_name VARCHAR(100) NOT NULL,
  routing_key VARCHAR(100) NOT NULL,
  payload TEXT NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  attempt_count INTEGER NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_error VARCHAR(1000),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  sent_at TIMESTAMPTZ
);
CREATE INDEX ix_outbox_due ON outbox_events(next_attempt_at, id) WHERE status = 'PENDING';

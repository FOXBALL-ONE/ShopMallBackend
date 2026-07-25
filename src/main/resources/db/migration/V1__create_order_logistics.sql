CREATE SEQUENCE IF NOT EXISTS shipment_no_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE IF NOT EXISTS orders (
    id BIGSERIAL PRIMARY KEY,
    order_no VARCHAR(32) NOT NULL CONSTRAINT uk_orders_no UNIQUE,
    customer_id BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL,
    items_subtotal NUMERIC(12,2) NOT NULL DEFAULT 0,
    shipping_fee NUMERIC(12,2) NOT NULL DEFAULT 0,
    tax_amount NUMERIC(12,2) NOT NULL DEFAULT 0,
    discount_amount NUMERIC(12,2) NOT NULL DEFAULT 0,
    total_amount NUMERIC(12,2) NOT NULL DEFAULT 0,
    currency VARCHAR(3) NOT NULL,
    payment_intent_id VARCHAR(64),
    recipient_name VARCHAR(100) NOT NULL,
    phone VARCHAR(16) NOT NULL,
    country_code VARCHAR(2) NOT NULL,
    state_or_province VARCHAR(100),
    city VARCHAR(100) NOT NULL,
    district VARCHAR(100),
    postal_code VARCHAR(20),
    address_line1 VARCHAR(255) NOT NULL,
    address_line2 VARCHAR(255),
    company VARCHAR(100),
    delivery_instructions VARCHAR(500),
    client_message VARCHAR(500),
    expires_at TIMESTAMPTZ,
    paid_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    shipped_at TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    cancel_reason VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_orders_status CHECK (status IN ('PENDING_PAYMENT','PAID','SHIPPED','DELIVERED','COMPLETED','CANCELLED')),
    CONSTRAINT ck_orders_currency CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_orders_payment_intent
    ON orders(payment_intent_id) WHERE payment_intent_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_orders_no ON orders(order_no);
CREATE INDEX IF NOT EXISTS idx_orders_customer_status ON orders(customer_id, status);
CREATE INDEX IF NOT EXISTS idx_orders_status_timeout ON orders(status, expires_at);

CREATE TABLE IF NOT EXISTS order_items (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL REFERENCES orders(id),
    product_id BIGINT NOT NULL,
    product_snapshot VARCHAR(2000) NOT NULL,
    unit_price NUMERIC(12,2) NOT NULL,
    quantity INTEGER NOT NULL,
    line_total NUMERIC(12,2) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_order_items_quantity CHECK (quantity > 0)
);
CREATE INDEX IF NOT EXISTS idx_order_items_order ON order_items(order_id);

CREATE TABLE IF NOT EXISTS domain_outbox (
    id BIGSERIAL PRIMARY KEY,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    acknowledged_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_domain_outbox_status CHECK (status IN ('PENDING','SENT','ACKNOWLEDGED','NEEDS_REPLAY','DEAD'))
);
CREATE INDEX IF NOT EXISTS idx_outbox_status_created ON domain_outbox(status, created_at);

CREATE TABLE IF NOT EXISTS stripe_webhook_events (
    event_id VARCHAR(255) PRIMARY KEY,
    event_type VARCHAR(128) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS shipments (
    id BIGSERIAL PRIMARY KEY,
    shipment_no VARCHAR(32) NOT NULL CONSTRAINT uk_shipments_no UNIQUE,
    order_id BIGINT NOT NULL REFERENCES orders(id),
    carrier_code VARCHAR(24) NOT NULL,
    tracking_no VARCHAR(64),
    tracking_no_normalized VARCHAR(64),
    status VARCHAR(24) NOT NULL,
    recipient_name VARCHAR(100) NOT NULL,
    phone VARCHAR(16) NOT NULL,
    country_code VARCHAR(2) NOT NULL,
    state_or_province VARCHAR(100),
    city VARCHAR(100) NOT NULL,
    district VARCHAR(100),
    postal_code VARCHAR(20),
    address_line1 VARCHAR(255) NOT NULL,
    address_line2 VARCHAR(255),
    company VARCHAR(100),
    delivery_instructions VARCHAR(500),
    shipped_at TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    carrier_label_url VARCHAR(512),
    tracking_url VARCHAR(512),
    last_track_status VARCHAR(64),
    last_track_at TIMESTAMPTZ,
    last_track_event_id VARCHAR(128),
    last_track_location VARCHAR(200),
    next_track_poll_at TIMESTAMPTZ,
    poll_lease_owner VARCHAR(128),
    poll_lease_until TIMESTAMPTZ,
    consecutive_track_failures INTEGER NOT NULL DEFAULT 0,
    last_track_error VARCHAR(500),
    created_by BIGINT NOT NULL,
    cancel_reason VARCHAR(200),
    note VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_shipments_status CHECK (status IN ('LABEL_PENDING','LABEL_CREATED','CANCEL_PENDING','IN_TRANSIT','OUT_FOR_DELIVERY','DELIVERED','CANCELLED')),
    CONSTRAINT ck_shipments_carrier CHECK (carrier_code IN ('MANUAL','FOUR_PX','YUN_EXPRESS','TRACK17'))
);
CREATE INDEX IF NOT EXISTS idx_shipment_no ON shipments(shipment_no);
CREATE INDEX IF NOT EXISTS idx_shipment_order_status ON shipments(order_id, status);
CREATE INDEX IF NOT EXISTS idx_shipment_poll_due ON shipments(next_track_poll_at, status);
CREATE UNIQUE INDEX IF NOT EXISTS uk_shipment_carrier_tracking
    ON shipments(carrier_code, tracking_no_normalized)
    WHERE tracking_no_normalized IS NOT NULL;

CREATE TABLE IF NOT EXISTS shipment_items (
    id BIGSERIAL PRIMARY KEY,
    shipment_id BIGINT NOT NULL REFERENCES shipments(id),
    order_item_id BIGINT NOT NULL REFERENCES order_items(id),
    order_item_snapshot VARCHAR(2000) NOT NULL,
    quantity INTEGER NOT NULL,
    allocation_status VARCHAR(16) NOT NULL DEFAULT 'ALLOCATED',
    released_at TIMESTAMPTZ,
    release_reason VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_shipment_items_quantity CHECK (quantity > 0),
    CONSTRAINT ck_shipment_items_allocation CHECK (allocation_status IN ('ALLOCATED','RELEASED'))
);
CREATE INDEX IF NOT EXISTS idx_shipment_items_shipment ON shipment_items(shipment_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_shipment_item_active
    ON shipment_items(order_item_id) WHERE allocation_status = 'ALLOCATED';

CREATE TABLE IF NOT EXISTS shipment_tracks (
    id BIGSERIAL PRIMARY KEY,
    shipment_id BIGINT NOT NULL REFERENCES shipments(id),
    carrier_event_id VARCHAR(128) NOT NULL,
    status_code VARCHAR(64) NOT NULL,
    normalized_status VARCHAR(32) NOT NULL,
    source VARCHAR(16) NOT NULL,
    location VARCHAR(200),
    description VARCHAR(500),
    occurred_at TIMESTAMPTZ NOT NULL,
    raw TEXT,
    received_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_track_shipment_event UNIQUE (shipment_id, carrier_event_id),
    CONSTRAINT ck_track_normalized_status CHECK (normalized_status IN ('IN_TRANSIT','OUT_FOR_DELIVERY','DELIVERED','EXCEPTION','UNKNOWN')),
    CONSTRAINT ck_track_source CHECK (source IN ('WEBHOOK','POLL','MANUAL'))
);
CREATE INDEX IF NOT EXISTS idx_track_shipment_ts
    ON shipment_tracks(shipment_id, occurred_at, carrier_event_id);

CREATE TABLE IF NOT EXISTS logistics_idempotency (
    id BIGSERIAL PRIMARY KEY,
    actor_id BIGINT NOT NULL,
    operation VARCHAR(48) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    shipment_id BIGINT NOT NULL REFERENCES shipments(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_logistics_idempotency UNIQUE (actor_id, operation, idempotency_key)
);

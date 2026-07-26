-- 订单幂等兜底表：Redis SETNX 在 afterCommit 回写失败、TTL 过期后会放行重复下单，
-- 此表以 DB 唯一约束兜底，保证相同 (customer_id, idempotency_key) 只创建一个订单。
CREATE TABLE IF NOT EXISTS order_idempotency (
    id BIGSERIAL PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    order_no VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_order_idempotency UNIQUE (customer_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_order_idempotency_order_no
    ON order_idempotency(order_no);

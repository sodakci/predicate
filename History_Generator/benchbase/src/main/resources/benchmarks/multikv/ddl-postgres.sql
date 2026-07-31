DROP TABLE IF EXISTS orders;
DROP TABLE IF EXISTS items;
DROP TABLE IF EXISTS users;

CREATE TABLE users (
    k     text PRIMARY KEY,
    value jsonb NOT NULL,
    CHECK (jsonb_typeof(value) = 'object'),
    CHECK (value->>'user_key' = k)
);

CREATE TABLE items (
    k     text PRIMARY KEY,
    value jsonb NOT NULL,
    CHECK (jsonb_typeof(value) = 'object'),
    CHECK (value->>'item_key' = k)
);

CREATE TABLE orders (
    k     text PRIMARY KEY,
    value jsonb NOT NULL,
    CHECK (jsonb_typeof(value) = 'object'),
    CHECK (value->>'order_key' = k)
);

CREATE INDEX idx_multikv_items_item_key ON items ((value->>'item_key'));
CREATE INDEX idx_multikv_items_stock ON items (((value->>'stock')::bigint));
CREATE INDEX idx_multikv_orders_item_key ON orders ((value->>'item_key'));

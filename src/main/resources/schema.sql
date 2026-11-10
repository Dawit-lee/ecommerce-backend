-- Runs on every startup after Hibernate's ddl-auto (spring.jpa.defer-datasource-initialization=true).
-- Postgres does not index FK columns, and '%kw%' LIKE needs a trigram index (see docs/measurements/LOADTEST.md).
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX IF NOT EXISTS idx_products_name_trgm ON products USING gin (lower(product_name) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_products_price ON products (price);
CREATE INDEX IF NOT EXISTS idx_products_category ON products (category_id);

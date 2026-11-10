-- Load-test seed data. Run AFTER the app has started once (Hibernate creates the schema and roles).
--   docker compose exec -T db psql -U postgres ecommerce -f - < seed.sql
-- random() inside subqueries is tied to the outer row ("+ 0 * g") or Postgres would evaluate it only once.
-- Deterministic: setseed() fixes random() so every run produces the same rows.
-- All seeded users share the password "password1" (the hash below is the BCrypt hash of it).
\set ON_ERROR_STOP on
\timing on
SELECT setseed(0.42);

BEGIN;
-- 50 categories
INSERT INTO categories (category_name)
SELECT 'Category-' || lpad(g::text, 3, '0') FROM generate_series(1, 50) g;

-- 1,000 sellers + 10,000 buyers
INSERT INTO users (username, email, password)
SELECT 'seller' || lpad(g::text, 5, '0'), 'seller' || g || '@loadtest.local',
       '$2a$10$mC8C0vx/GUtC1u01k4s.H.JCFb2bhkwjeexI42k2wBIUFyOOMO8RG'
FROM generate_series(1, 1000) g;
INSERT INTO users (username, email, password)
SELECT 'buyer' || lpad(g::text, 5, '0'), 'buyer' || g || '@loadtest.local',
       '$2a$10$mC8C0vx/GUtC1u01k4s.H.JCFb2bhkwjeexI42k2wBIUFyOOMO8RG'
FROM generate_series(1, 10000) g;
INSERT INTO user_role (user_id, role_id)
SELECT u.user_id, r.role_id FROM users u JOIN roles r
  ON (u.username LIKE 'seller%' AND u.username <> 'seller1' AND r.role_name = 'ROLE_SELLER')
  OR (u.username LIKE 'buyer%' AND r.role_name = 'ROLE_USER');

INSERT INTO addresses (street, building_name, city, state, country, pincode, user_id)
SELECT 'Street ' || user_id, 'Building ' || user_id, 'Seoul', 'Seoul', 'Korea', '0' || (10000 + user_id % 90000), user_id
FROM users WHERE username LIKE 'buyer%';

-- 1,000,000 products. Category and seller are skewed (power distribution) so a few categories/sellers
-- own most products, like a real marketplace.
CREATE TEMP TABLE cat_ids AS SELECT row_number() OVER (ORDER BY category_id) AS n, category_id FROM categories WHERE category_name LIKE 'Category-%';
CREATE TEMP TABLE seller_ids AS SELECT row_number() OVER (ORDER BY user_id) AS n, user_id FROM users WHERE username LIKE 'seller0%';

INSERT INTO products (product_id, product_name, description, image, price, discount, special_price, quantity, version, category_id, seller_id)
SELECT g,
       (ARRAY['Wireless','Smart','Premium','Portable','Classic','Compact','Ultra','Eco','Pro','Mini'])[1 + floor(random()*10)::int]
         || ' ' ||
       (ARRAY['Keyboard','Mouse','Monitor','Laptop','Headphone','Speaker','Camera','Watch','Charger','Cable',
              'Backpack','Jacket','Sneakers','Tumbler','Lamp','Chair','Desk','Blender','Kettle','Toaster',
              'Notebook','Pen','Bottle','Tent','Pillow','Blanket','Mug','Perfume','Shampoo','Vitamin'])[1 + floor(random()*30)::int]
         || ' ' || upper(substr(md5(g::text), 1, 6)),
       'Load-test product number ' || g,
       'default.png',
       p.price, p.discount, p.price - p.price * p.discount * 0.01,
       floor(random() * 1000)::int, 0,
       c.category_id, s.user_id
FROM generate_series(1, 1000000) g
CROSS JOIN LATERAL (SELECT (1000 + floor(random() * 499) * 1000)::float8 AS price,
                           (floor(random() * 5) * 10)::float8 + 0 * g AS discount,
                           1 + floor(50 * power(random(), 2))::int + 0 * g AS cn,
                           1 + floor(1000 * power(random(), 3))::int + 0 * g AS sn) p
JOIN cat_ids c ON c.n = p.cn
JOIN seller_ids s ON s.n = p.sn;

-- 5,000 buyers have a cart with 1..5 items
INSERT INTO carts (user_id, total_price)
SELECT user_id, 0 FROM users WHERE username LIKE 'buyer%' AND substr(username, 6)::int <= 5000;
INSERT INTO cart_items (cart_id, product_id, quantity, discount, product_price)
SELECT c.cart_id, pid, 1, pr.discount, pr.special_price
FROM carts c
CROSS JOIN LATERAL (SELECT DISTINCT 1 + floor(random() * 1000000)::bigint + c.cart_id * 0 AS pid
                    FROM generate_series(1, 1 + floor(random() * 5)::int + 0 * c.cart_id::int)) x
JOIN products pr ON pr.product_id = x.pid;
UPDATE carts c SET total_price = s.total FROM (SELECT cart_id, sum(product_price) total FROM cart_items GROUP BY cart_id) s
WHERE s.cart_id = c.cart_id;

-- Hibernate uses a pooled sequence (allocationSize 50) for products; move it past the seeded ids.
SELECT setval('products_seq', 1000100);
COMMIT;

VACUUM ANALYZE;
SELECT (SELECT count(*) FROM products) products, (SELECT count(*) FROM users) users,
       (SELECT count(*) FROM carts) carts, (SELECT count(*) FROM cart_items) cart_items;

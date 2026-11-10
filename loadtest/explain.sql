-- The SQL Hibernate generates for GET /api/public/products (taken from pg_stat_statements), with literal params.
\echo '=== list: count'
EXPLAIN (ANALYZE, BUFFERS) select count(p1_0.product_id) from products p1_0 where 1=1;
\echo '=== list: page 50'
EXPLAIN (ANALYZE, BUFFERS) select * from products p1_0 where 1=1 order by p1_0.product_id offset 500 rows fetch first 10 rows only;
\echo '=== keyword: count'
EXPLAIN (ANALYZE, BUFFERS) select count(p1_0.product_id) from products p1_0 where 1=1 and lower(p1_0.product_name) like '%keyboard%' escape '';
\echo '=== keyword: page 5'
EXPLAIN (ANALYZE, BUFFERS) select * from products p1_0 where 1=1 and lower(p1_0.product_name) like '%keyboard%' escape '' order by p1_0.product_id offset 50 rows fetch first 10 rows only;
\echo '=== category: count'
EXPLAIN (ANALYZE, BUFFERS) select count(p1_0.product_id) from products p1_0 join categories c1_0 on c1_0.category_id=p1_0.category_id where 1=1 and c1_0.category_name like 'Category-030' escape '';
\echo '=== category: page 5'
EXPLAIN (ANALYZE, BUFFERS) select p1_0.* from products p1_0 join categories c1_0 on c1_0.category_id=p1_0.category_id where 1=1 and c1_0.category_name like 'Category-030' escape '' order by p1_0.product_id offset 50 rows fetch first 10 rows only;
\echo '=== price sort: page 50'
EXPLAIN (ANALYZE, BUFFERS) select * from products p1_0 where 1=1 order by p1_0.price desc offset 500 rows fetch first 10 rows only;

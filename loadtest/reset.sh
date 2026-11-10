#!/usr/bin/env bash
# Recreate DB from scratch: fresh volume -> app creates schema/roles -> seed 1M products.
set -euo pipefail
cd "$(dirname "$0")"
docker compose down -v
docker compose up -d --build db prometheus app
until curl -sf localhost:18080/actuator/health >/dev/null; do sleep 2; done
docker compose exec -T db psql -U postgres ecommerce -f - < seed.sql
docker compose exec -T db psql -U postgres ecommerce -c 'CREATE EXTENSION IF NOT EXISTS pg_stat_statements'

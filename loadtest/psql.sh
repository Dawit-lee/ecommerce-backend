#!/usr/bin/env bash
# psql into the load-test DB: ./psql.sh -c "select ..."
cd "$(dirname "$0")" && exec docker compose exec -T db psql -U postgres ecommerce "$@"

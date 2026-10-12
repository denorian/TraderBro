#!/usr/bin/env bash
# TraderBro — run Liquibase migrations against the database.
#
# Migrations live in ./migrations/db.changelog-master.sql and are applied by the
# docker-compose `migrate` service. This script is the standalone equivalent for
# local runs / CI without going through docker-compose. It uses the official
# Liquibase Docker image, so no local Liquibase install is required.
#
# Connection settings come from the same env vars as docker-compose:
#   POSTGRES_HOST (default localhost), POSTGRES_DB, POSTGRES_USER, POSTGRES_PASSWORD
set -euo pipefail

cd "$(dirname "$0")/.."

IMAGE="${LIQUIBASE_IMAGE:-liquibase/liquibase:${LIQUIBASE_VERSION:-4.31.1}}"
DB_HOST="${POSTGRES_HOST:-localhost}"
DB_PORT="${POSTGRES_PORT:-25432}"
DB_NAME="${POSTGRES_DB:-traderbro}"
DB_USER="${POSTGRES_USER:-traderbro}"
DB_PASS="${POSTGRES_PASSWORD:-traderbro}"
DB_URL="jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}"

echo "Applying Liquibase migrations from ./migrations to ${DB_URL} ..."

docker run --rm \
  -v "$(pwd)/migrations:/liquibase/changelog" \
  -e "LIQUIBASE_COMMAND_URL=${DB_URL}" \
  -e "LIQUIBASE_COMMAND_USERNAME=${DB_USER}" \
  -e "LIQUIBASE_COMMAND_PASSWORD=${DB_PASS}" \
  -e "LIQUIBASE_COMMAND_CHANGELOG_FILE=db.changelog-master.sql" \
  -e "LIQUIBASE_SEARCH_PATH=/liquibase/changelog" \
  "$IMAGE" update
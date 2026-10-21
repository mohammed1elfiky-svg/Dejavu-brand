#!/bin/sh
# Applies migrations and base data on every start (both are idempotent),
# optionally loads demo data, then starts the API.
set -e
npx prisma migrate deploy
npx prisma db seed
if [ "$DEMO_DATA" = "true" ]; then
  node dist/demo/seed-demo.js
fi
exec node dist/main.js

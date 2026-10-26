#!/usr/bin/env bash
# Rebuild and restart after `git pull` (migrations run on start).
set -euo pipefail
cd "$(dirname "$0")"
docker build -t dejavu-api:local ../backend
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d

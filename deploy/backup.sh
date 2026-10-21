#!/bin/sh
# Daily database backup, keeps 14 days. Add to cron on the server:
#   0 3 * * * cd /opt/dejavu/deploy && ./backup.sh
set -e
cd "$(dirname "$0")"
stamp=$(date +%Y-%m-%d_%H%M)
docker compose -f docker-compose.prod.yml --env-file .env.prod exec -T postgres \
  pg_dump -U dejavu -Fc dejavu > "backups/dejavu_$stamp.dump"
find backups -name 'dejavu_*.dump' -mtime +14 -delete
echo "backups/dejavu_$stamp.dump"

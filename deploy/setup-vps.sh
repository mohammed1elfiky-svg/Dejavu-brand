#!/usr/bin/env bash
# One-shot setup of the Dejavu API on a fresh Ubuntu/Debian VPS (Contabo, Hetzner, ...).
# Run as root:
#   curl -fsSL https://raw.githubusercontent.com/mohammed1elfiky-svg/Dejavu-brand/claude/accounting-system-setup-u5okpy/deploy/setup-vps.sh -o setup-vps.sh
#   bash setup-vps.sh [domain]
# Without a domain it uses <ip-with-dashes>.sslip.io, which points at this server and still gets a real HTTPS certificate.
set -euo pipefail

BRANCH="${BRANCH:-claude/accounting-system-setup-u5okpy}"
DIR=/opt/dejavu
[ "$(id -u)" = 0 ] || { echo "Run as root"; exit 1; }

if ! command -v docker >/dev/null; then
  curl -fsSL https://get.docker.com | sh
fi
apt-get install -y -qq git ufw >/dev/null
ufw allow OpenSSH >/dev/null; ufw allow 80/tcp >/dev/null; ufw allow 443/tcp >/dev/null; ufw --force enable >/dev/null

if [ -d "$DIR/.git" ]; then git -C "$DIR" fetch -q origin "$BRANCH" && git -C "$DIR" checkout -q -B "$BRANCH" "origin/$BRANCH"
else git clone -q -b "$BRANCH" https://github.com/mohammed1elfiky-svg/Dejavu-brand.git "$DIR"; fi
cd "$DIR/deploy"

IP=$(curl -fsS4 https://api.ipify.org)
DOMAIN="${1:-${IP//./-}.sslip.io}"

if [ ! -f .env.prod ]; then
  read -rp "Admin email: " ADMIN_EMAIL
  read -rsp "Admin password (8+ chars): " ADMIN_PASS; echo
  read -rp "Gemini API key (Enter to skip): " GEMINI
  cat > .env.prod <<ENV
DOMAIN=$DOMAIN
API_IMAGE=dejavu-api:local
POSTGRES_PASSWORD=$(openssl rand -hex 24)
JWT_ACCESS_SECRET=$(openssl rand -base64 48 | tr -d '\n')
SEED_ADMIN_EMAIL=$ADMIN_EMAIL
SEED_ADMIN_PASSWORD=$ADMIN_PASS
DEMO_DATA=false
SWAGGER_ENABLED=false
STORE_TIMEZONE=Asia/Aden
PAYMENT_GATEWAY=none
CARD_PAYMENT_TIMEOUT_MINUTES=60
FCM_SERVICE_ACCOUNT=
GEMINI_API_KEY=$GEMINI
ENV
  chmod 600 .env.prod
fi

docker build -t dejavu-api:local ../backend
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d
(crontab -l 2>/dev/null | grep -v backup.sh; echo "0 3 * * * cd $DIR/deploy && ./backup.sh") | crontab -
chmod +x backup.sh

echo
echo "Done. API: https://$DOMAIN/api/v1/   (health: https://$DOMAIN/api/v1/health)"
echo "Update later:  cd $DIR && git pull && bash deploy/update.sh"

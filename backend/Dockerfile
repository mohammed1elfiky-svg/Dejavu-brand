# Dejavu API — image for demos, staging and local runs.
FROM node:22-alpine

WORKDIR /app

COPY package.json package-lock.json ./
COPY prisma ./prisma
COPY prisma.config.ts ./
# `prisma generate` (postinstall) only needs a syntactically valid URL at build time.
RUN DATABASE_URL=postgresql://build:build@localhost:5432/build npm ci --no-audit --no-fund

COPY tsconfig.json tsconfig.build.json nest-cli.json ./
COPY src ./src
RUN npm run build

COPY docker-entrypoint.sh ./
RUN chmod +x docker-entrypoint.sh

ENV NODE_ENV=production PORT=3000
EXPOSE 3000
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s \
  CMD wget -qO- "http://localhost:${PORT}/api/v1/health" || exit 1
ENTRYPOINT ["./docker-entrypoint.sh"]

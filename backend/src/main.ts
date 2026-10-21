import 'dotenv/config';
import { NestFactory } from '@nestjs/core';
import { NestExpressApplication } from '@nestjs/platform-express';
import type { Express } from 'express';
import { AppModule } from './app.module';
import { configureApp, setupSwagger } from './app.setup';

async function bootstrap() {
  const app = configureApp(await NestFactory.create<NestExpressApplication>(AppModule));
  // Behind a reverse proxy (nginx, load balancer) rate limiting must see the client IP.
  if (process.env.TRUST_PROXY) app.set('trust proxy', Number(process.env.TRUST_PROXY) || process.env.TRUST_PROXY);
  app.enableCors();
  if (process.env.SWAGGER_ENABLED !== 'false') {
    setupSwagger(app);
    (app.getHttpAdapter().getInstance() as Express).get('/', (_req, res) => res.redirect('/docs'));
  }
  const port = Number(process.env.PORT ?? 3000);
  await app.listen(port);
  console.log(`Dejavu API listening on http://localhost:${port}/api/v1 (docs: /docs)`);
}

void bootstrap();

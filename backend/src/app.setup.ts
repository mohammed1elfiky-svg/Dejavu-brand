import { INestApplication, ValidationPipe } from '@nestjs/common';
import { DocumentBuilder, SwaggerModule } from '@nestjs/swagger';
import { DEMO_CUSTOMERS, DEMO_PASSWORD, DEMO_STAFF } from './demo/demo-accounts';

/** Shared by main.ts and the e2e tests so both run the exact same pipeline. */
export function configureApp<T extends INestApplication>(app: T): T {
  app.setGlobalPrefix('api/v1');
  app.enableShutdownHooks();
  app.useGlobalPipes(
    new ValidationPipe({
      whitelist: true,
      forbidNonWhitelisted: true,
      transform: true,
    }),
  );
  return app;
}

export function setupSwagger(app: INestApplication) {
  const staff = DEMO_STAFF.map((s) => `| ${s.role} | \`${s.email}\` | \`${s.password}\` |`).join('\n');
  const customers = DEMO_CUSTOMERS.map((c) => `| ${c.name} | \`${c.phone}\` | \`${DEMO_PASSWORD}\` |`).join('\n');
  const description = `
Accounting & commerce API for the Dejavu customer and admin apps.

### How to try it
1. Open **Auth → POST /auth/staff/login** (or \`/auth/customer/login\`), click **Try it out**, and log in with an account below.
2. Copy \`accessToken\` from the response, click **Authorize** (top right) and paste it.
3. Call any endpoint. Good starting points: \`GET /admin/orders\`, \`GET /admin/orders/{id}\` (shows the journal
   entries each order produced), \`GET /admin/reports/trial-balance\`, \`GET /store/products\`.

### Demo accounts (only when demo data is loaded)
| Staff role | Email | Password |
|---|---|---|
${staff}

| Customer | Phone | Password |
|---|---|---|
${customers}
`;
  const config = new DocumentBuilder()
    .setTitle('Dejavu API')
    .setDescription(description)
    .setVersion('0.2.0')
    .addBearerAuth()
    .build();
  SwaggerModule.setup('docs', app, SwaggerModule.createDocument(app, config), {
    customSiteTitle: 'Dejavu API',
    swaggerOptions: { persistAuthorization: true, docExpansion: 'none', tagsSorter: 'alpha', tryItOutEnabled: true },
  });
}

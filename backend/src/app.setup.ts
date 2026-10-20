import { INestApplication, ValidationPipe } from '@nestjs/common';
import { DocumentBuilder, SwaggerModule } from '@nestjs/swagger';

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
  const config = new DocumentBuilder()
    .setTitle('Dejavu API')
    .setDescription('Accounting & commerce API for the Dejavu customer and admin apps')
    .setVersion('0.2.0')
    .addBearerAuth()
    .build();
  SwaggerModule.setup('docs', app, SwaggerModule.createDocument(app, config));
}

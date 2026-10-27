import { ExcelModule } from './excel/excel.module';
import { MarketingModule } from './marketing/marketing.module';
import { ChatModule } from './chat/chat.module';
import { StorefrontModule } from './storefront/storefront.module';
import { Module } from '@nestjs/common';
import { ConfigModule } from '@nestjs/config';
import { APP_FILTER, APP_GUARD } from '@nestjs/core';
import { ThrottlerGuard, ThrottlerModule } from '@nestjs/throttler';
import { AccountingModule } from './accounting/accounting.module';
import { AuthModule } from './auth/auth.module';
import { CatalogModule } from './catalog/catalog.module';
import { CommonModule } from './common/common.module';
import { PrismaExceptionFilter } from './common/filters/prisma-exception.filter';
import { JwtAuthGuard } from './common/guards/jwt-auth.guard';
import { PermissionsGuard } from './common/guards/permissions.guard';
import { validateEnv } from './config/env';
import { ExpensesModule } from './expenses/expenses.module';
import { FilesModule } from './files/files.module';
import { HealthController } from './health/health.controller';
import { AccountModule } from './account/account.module';
import { InventoryModule } from './inventory/inventory.module';
import { NotificationsModule } from './notifications/notifications.module';
import { PrismaModule } from './prisma/prisma.module';
import { PurchasingModule } from './purchasing/purchasing.module';
import { ReportsModule } from './reports/reports.module';
import { SalesModule } from './sales/sales.module';
import { SmsModule } from './sms/sms.module';
import { SystemModule } from './system/system.module';
import { HomeModule } from './home/home.module';
import { TreasuryModule } from './treasury/treasury.module';
import { UsersModule } from './users/users.module';

@Module({
  imports: [
    ConfigModule.forRoot({ isGlobal: true, validate: validateEnv }),
    ThrottlerModule.forRoot([{ name: 'default', ttl: 60_000, limit: 300 }]),
    PrismaModule,
    AccountModule,
    CommonModule,
    NotificationsModule,
    AuthModule,
    UsersModule,
    AccountingModule,
    FilesModule,
    TreasuryModule,
    CatalogModule,
    InventoryModule,
    SalesModule,
    PurchasingModule,
    ExpensesModule,
    ReportsModule,
    SystemModule,
    HomeModule,
    SmsModule,
    StorefrontModule,
    ChatModule,
    MarketingModule,
    ExcelModule,
  ],
  controllers: [HealthController],
  providers: [
    // Order matters: rate limit → authenticate → authorise.
    { provide: APP_GUARD, useClass: ThrottlerGuard },
    { provide: APP_GUARD, useClass: JwtAuthGuard },
    { provide: APP_GUARD, useClass: PermissionsGuard },
    { provide: APP_FILTER, useClass: PrismaExceptionFilter },
  ],
})
export class AppModule {}

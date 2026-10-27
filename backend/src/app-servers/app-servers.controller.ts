import { Body, Controller, Get, Post, Put } from '@nestjs/common';
import { ApiProperty, ApiTags } from '@nestjs/swagger';
import { SkipThrottle } from '@nestjs/throttler';
import { ArrayMaxSize, IsArray, IsString, MaxLength } from 'class-validator';
import { Public, RequirePermissions } from '../common/decorators/auth.decorators';
import { AppServersService } from './app-servers.service';

export class AppServersDto {
  @ApiProperty({ example: ['https://dejavu.zapto.org/api/v1/'] })
  @IsArray()
  @ArrayMaxSize(5)
  @IsString({ each: true })
  @MaxLength(200, { each: true })
  servers: string[];
}

@ApiTags('Store · Servers')
@Public()
@SkipThrottle()
@Controller('store/servers')
export class StoreServersController {
  constructor(private readonly servers: AppServersService) {}

  /** The addresses the customer app may use, in order. */
  @Get()
  list() {
    return this.servers.publicList();
  }
}

@ApiTags('Admin · Customer app')
@RequirePermissions('settings.manage')
@Controller('admin/app-servers')
export class AdminServersController {
  constructor(private readonly servers: AppServersService) {}

  @Get()
  view() {
    return this.servers.adminView();
  }

  @Put()
  set(@Body() dto: AppServersDto) {
    return this.servers.set(dto.servers);
  }

  /** Rewrites the GitHub copy now (e.g. after adding the token). */
  @Post('sync')
  async sync() {
    await this.servers.syncDirectory();
    return this.servers.adminView();
  }
}

package com.shoppilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MySQL 数据源配置。
 * 注意：本机 MySQL 需要先执行 pilot-infra/scripts/mysql-start.sh start 启动。
 */
@ConfigurationProperties(prefix = "pilot.datasource")
public record DataSourceProperties(
        String host,
        int port,
        String database,
        String username,
        String password,
        int poolInitialSize,
        int poolMaxSize
) {
    public String r2dbcUrl() {
        return "r2dbc:mysql://%s:%d/%s?serverZoneId=Asia/Shanghai&useServerPrepareStatement=false"
                .formatted(host, port, database);
    }
}

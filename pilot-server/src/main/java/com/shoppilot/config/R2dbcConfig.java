package com.shoppilot.config;

import io.asyncer.r2dbc.mysql.MySqlConnectionConfiguration;
import io.asyncer.r2dbc.mysql.MySqlConnectionFactory;
import io.r2dbc.spi.ConnectionFactory;import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.config.AbstractR2dbcConfiguration;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;

import java.time.Duration;

/**
 * 用代码方式构建 ConnectionFactory，而不是写 spring.r2dbc.url。
 * <p>
 * 原因一：项目里存在 SERVER__PORT 这类宽松绑定的环境变量，显式构造可以避免
 * 配置被意外覆盖，出问题时定位也更直接。
 * <p>
 * 原因二（关键）：必须显式设 tinyInt1isBit=false。
 * R2DBC MySQL 驱动默认把 TINYINT(1) 当成 Boolean 读出来，而项目里
 * on_shelf / selected / status / is_complete 全是 TINYINT(1)。
 * 联调时真实踩到的现象是：商品明明 on_shelf=1，加购却报「商品已下架」——
 * 因为取到的是 Boolean.TRUE，而代码判断的是 Number。关掉这个转换后，
 * 所有 TINYINT(1) 统一返回数值，判断逻辑不再有歧义。
 */
@Configuration
@EnableR2dbcRepositories(basePackages = "com.shoppilot.repository")
public class R2dbcConfig extends AbstractR2dbcConfiguration {

    private final DataSourceProperties props;

    public R2dbcConfig(DataSourceProperties props) {
        this.props = props;
    }

    @Override
    @Bean
    public ConnectionFactory connectionFactory() {
        MySqlConnectionConfiguration config = MySqlConnectionConfiguration.builder()
                .host(props.host())
                .port(props.port())
                .database(props.database())
                .user(props.username())
                .password(props.password())
                .connectTimeout(Duration.ofSeconds(5))
                // 关键：让 TINYINT(1) 保持数值语义，不要转成 Boolean
                .tinyInt1isBit(false)
                // 时区必须固定，否则 DATETIME 在不同机器上会偏移
                .serverZoneId(java.time.ZoneId.of("Asia/Shanghai"))
                .build();

        return MySqlConnectionFactory.from(config);
    }
}

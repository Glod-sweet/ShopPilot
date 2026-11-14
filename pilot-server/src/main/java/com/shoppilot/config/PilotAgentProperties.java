package com.shoppilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Python Agent 服务的连接配置。
 * 注意 response-timeout 必须大于前端的 60s，否则会出现"回答到一半断掉"。
 */
@ConfigurationProperties(prefix = "pilot.agent")
public record PilotAgentProperties(
        String baseUrl,
        String internalToken,
        int connectTimeoutMs,
        int responseTimeoutMs
) {
}

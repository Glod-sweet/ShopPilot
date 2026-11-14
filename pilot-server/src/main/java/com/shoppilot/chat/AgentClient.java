package com.shoppilot.chat;

import com.shoppilot.chat.dto.AgentChatRequest;
import com.shoppilot.chat.dto.ChatRequest;
import com.shoppilot.chat.service.ChatSessionService;
import com.shoppilot.config.PilotAgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 与 Python Agent 通信的唯一出口。
 * 这一层的职责只有两个：组装请求、把 SSE 原样透传回来。
 * 绝不做业务判断，绝不做内容加工。
 * <p>
 * 为什么 history 和 slots 由 Java 传：Python 设计成无状态服务，
 * 不连数据库、不查会话。每次请求都带全量上下文进来，它才能随时重启扩容。
 */
@Service
public class AgentClient {

    private static final Logger log = LoggerFactory.getLogger(AgentClient.class);

    private final WebClient webClient;
    private final PilotAgentProperties props;
    private final ChatSessionService sessionService;

    public AgentClient(WebClient agentWebClient, PilotAgentProperties props,
                       ChatSessionService sessionService) {
        this.webClient = agentWebClient;
        this.props = props;
        this.sessionService = sessionService;
    }

    /**
     * 发起流式对话。返回的 Flux 由 Controller 直接写给前端，中间不缓冲。
     *
     * @param sessionId 数据库主键
     * @param sessionNo 对外会话号，透传给 Python 便于日志串联
     * @param traceId   全链路追踪号
     */
    public Flux<ServerSentEvent<String>> streamChat(long userId, long sessionId, String sessionNo,
                                                    ChatRequest req, String traceId) {
        return sessionService.prepareContext(sessionId)
                .flatMapMany(ctx -> {
                    AgentChatRequest body = new AgentChatRequest(
                            userId,
                            sessionNo,
                            req.message(),
                            ctx.history(),
                            toAgentSlots(ctx.slots()),
                            traceId
                    );

                    log.debug("→ agent chat sessionId={} traceId={} history={} slots={}",
                            sessionNo, traceId, ctx.history().size(), ctx.slots());

                    return webClient.post()
                            .uri("/agent/chat")
                            .header("X-User-Id", String.valueOf(userId))
                            .header("X-Trace-Id", traceId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.TEXT_EVENT_STREAM)
                            .bodyValue(body)
                            .retrieve()
                            .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                            });
                })
                .doOnError(e -> log.error("agent stream failed traceId={}", traceId, e));
    }

    /** 库里的槽位结构 → Python 契约要求的 {category, budget, scene} 三字段 */
    private AgentChatRequest.Slots toAgentSlots(Map<String, Object> slots) {
        if (slots == null || slots.isEmpty()) {
            return new AgentChatRequest.Slots(null, null, null);
        }
        return new AgentChatRequest.Slots(
                str(slots.get("category")),
                str(slots.get("budget")),
                str(slots.get("scene")));
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    /** 探测 Agent 是否存活，供 /api/health 汇总各端状态 */
    public Mono<Boolean> isAgentUp() {
        return webClient.get()
                .uri("/agent/health")
                .retrieve()
                .bodyToMono(String.class)
                .map(body -> true)
                .timeout(Duration.ofMillis(props.connectTimeoutMs() + 500L))
                .onErrorResume(e -> {
                    log.debug("agent 健康检查失败: {}", e.getMessage());
                    return Mono.just(false);
                });
    }

    /** 保留给单元测试与调试：不发请求只看组装结果 */
    List<String> describe() {
        return List.of(props.baseUrl(), String.valueOf(props.responseTimeoutMs()));
    }
}

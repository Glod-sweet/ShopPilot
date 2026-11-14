package com.shoppilot.chat;

import com.shoppilot.chat.dto.AgentChatRequest;
import com.shoppilot.chat.dto.ChatRequest;
import com.shoppilot.config.PilotAgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;

/**
 * 与 Python Agent 通信的唯一出口。
 * 这一层的职责只有两个：组装请求、把 SSE 原样透传回来。
 * 绝不做业务判断，绝不做内容加工。
 */
@Service
public class AgentClient {

    private static final Logger log = LoggerFactory.getLogger(AgentClient.class);

    private final WebClient webClient;

    public AgentClient(WebClient agentWebClient, PilotAgentProperties props) {
        this.webClient = agentWebClient;
    }

    /**
     * 发起流式对话。返回的 Flux 由 Controller 直接写给前端，中间不缓冲。
     */
    public Flux<ServerSentEvent<String>> streamChat(Long userId, ChatRequest req, String sessionId) {
        String traceId = UUID.randomUUID().toString();

        AgentChatRequest body = new AgentChatRequest(
                userId,
                sessionId,
                req.message(),
                List.of(),
                new AgentChatRequest.Slots(
                        req.slotsOrEmpty().category(),
                        req.slotsOrEmpty().budget(),
                        req.slotsOrEmpty().scene()
                ),
                traceId
        );

        log.debug("→ agent chat, sessionId={}, traceId={}", sessionId, traceId);

        return webClient.post()
                .uri("/agent/chat")
                .header("X-User-Id", String.valueOf(userId))
                .header("X-Trace-Id", traceId)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .doOnError(e -> log.error("agent stream failed, traceId={}", traceId, e));
    }
}

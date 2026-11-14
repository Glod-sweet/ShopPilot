package com.shoppilot.chat;

import com.shoppilot.chat.dto.ChatRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * 前端唯一入口。
 * 注意：必须是 WebFlux 的 Flux 直接返回，绝不能在中间 collectList()，
 * 否则流式会退化成"憋完整再吐"。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final AgentClient agentClient;

    public ChatController(AgentClient agentClient) {
        this.agentClient = agentClient;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<String>>> stream(@Valid @RequestBody ChatRequest req) {
        // TODO 接入鉴权后从 SecurityContext 取真实用户
        Long userId = 1L;

        String sessionId = (req.sessionId() == null || req.sessionId().isBlank())
                ? "s_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16)
                : req.sessionId();

        Flux<ServerSentEvent<String>> stream = agentClient.streamChat(userId, req, sessionId);

        return ResponseEntity.ok()
                .header("X-Session-Id", sessionId)
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no")
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(stream);
    }
}

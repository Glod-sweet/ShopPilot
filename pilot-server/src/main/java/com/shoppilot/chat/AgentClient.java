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

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 与 Python Agent 通信的唯一出口。
 * <p>
 * 这一层的职责只有两个：组装请求、把 SSE 原样透传回来。
 * 绝不做业务判断，绝不做内容加工。
 * <p>
 * 「原样透传」是硬约束（handbook 第十一章第 3 条）：
 * 收到 Python 的流立刻推给前端，中间不能 collectList()、不能 buffer()，
 * 否则用户会看到「转圈 3 秒然后一次性蹦出一整段」。
 */
@Service
public class AgentClient {

    private static final Logger log = LoggerFactory.getLogger(AgentClient.class);

    private final WebClient webClient;

    public AgentClient(WebClient agentWebClient, PilotAgentProperties props) {
        this.webClient = agentWebClient;
    }

    /**
     * 发起流式对话（无上下文版本，保留给不需要历史/槽位的场景）。
     */
    public Flux<ServerSentEvent<String>> streamChat(Long userId, ChatRequest req, String sessionId) {
        return streamChat(userId, req, sessionId, List.of(), Map.of(), null);
    }

    /**
     * 发起流式对话，带上会话上下文。
     *
     * @param history 由 Java 从数据库读出后传入，Python 不查库
     * @param slots   上一轮槽位状态，由 Java 持久化，Python 无状态
     * @param image   多模态图片（base64 / data URL / http 地址），可为 null
     */
    public Flux<ServerSentEvent<String>> streamChat(Long userId, ChatRequest req, String sessionId,
                                                    List<Map<String, String>> history,
                                                    Map<String, Object> slots,
                                                    String image) {
        String traceId = UUID.randomUUID().toString();

        List<AgentChatRequest.Message> messages = history == null ? List.of()
                : history.stream()
                .map(h -> new AgentChatRequest.Message(
                        String.valueOf(h.getOrDefault("role", "user")),
                        String.valueOf(h.getOrDefault("content", ""))))
                .toList();

        AgentChatRequest body = new AgentChatRequest(
                userId,
                sessionId,
                req.message(),
                messages,
                new AgentChatRequest.Slots(
                        str(slots.get("category")),
                        str(slots.get("budget")),
                        str(slots.get("scene"))
                ),
                req.compareIdsOrEmpty(),
                traceId,
                image
        );

        if (req.isCompareMode()) {
            log.debug("→ agent chat 对比模式 ids={}", req.compareIdsOrEmpty());
        }

        log.debug("→ agent chat, userId={}, sessionId={}, history={}, traceId={}",
                userId, sessionId, messages.size(), traceId);

        return webClient.post()
                .uri("/agent/chat")
                .header("X-User-Id", String.valueOf(userId))
                .header("X-Trace-Id", traceId)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                // 只记日志，不改内容 —— 错误事件由 Python 自己产出，Java 不越俎代庖
                .doOnError(e -> log.error("agent stream failed, traceId={}", traceId, e));
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}

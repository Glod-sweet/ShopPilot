package com.shoppilot.chat;

import com.shoppilot.chat.dto.ChatRequest;
import com.shoppilot.chat.service.ChatSessionService;
import com.shoppilot.security.AuthWebFilter;
import com.shoppilot.security.UserContext;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 前端唯一入口。
 * <p>
 * 三条硬约束：
 * <ol>
 *   <li>必须返回 WebFlux 的 {@code Flux}，绝不能在中间 {@code collectList()}，
 *       否则流式会退化成「憋完整再吐」。</li>
 *   <li>用户身份来自 {@link AuthWebFilter} 解析的 JWT，不再硬编码 userId。</li>
 *   <li>历史与槽位由 Java 从库里读，Python 无状态；流结束后由 Java 落库。</li>
 * </ol>
 * 这三条对应 handbook 第十一章的第 1、3 条原则。
 * <p>
 * 为什么返回 {@code Mono<ResponseEntity<Flux<...>>>} 而不是直接
 * {@code ResponseEntity<Flux<...>>}：会话初始化要查库，属于异步操作。
 * 若在方法体里 {@code .block()} 取会话号，会阻塞 Netty 的事件循环线程，
 * 几个并发就能把服务拖死。用 Mono 包一层，等会话就绪后再组装响应头。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final AgentClient agentClient;
    private final ChatSessionService sessionService;

    public ChatController(AgentClient agentClient, ChatSessionService sessionService) {
        this.agentClient = agentClient;
        this.sessionService = sessionService;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Mono<ResponseEntity<Flux<ServerSentEvent<String>>>> stream(
            @Valid @RequestBody ChatRequest req,
            ServerWebExchange exchange) {

        // 鉴权已在 AuthWebFilter 完成，这里取真实用户（dev-mode 下是配置的 dev 用户）
        UserContext user = AuthWebFilter.currentUser(exchange);
        long userId = user.userId();

        return prepareStream(req, userId)
                // 上下文准备失败不该让整个请求 500：降级成空上下文，
                // Agent 仍能回答，只是少了历史记忆
                .onErrorResume(e -> {
                    log.error("会话上下文准备失败，降级为空上下文", e);
                    String fallbackNo = req.sessionId() == null || req.sessionId().isBlank()
                            ? ChatSessionService.newSessionNo() : req.sessionId();
                    return Mono.just(new PreparedStream(fallbackNo, -1L, List.of(), Map.of()));
                })
                .map(prepared -> {
                    Flux<ServerSentEvent<String>> raw = agentClient.streamChat(
                            userId, req, prepared.sessionNo(),
                            prepared.history(), prepared.slots(), req.image());

                    return ResponseEntity.ok()
                            .header("X-Session-Id", prepared.sessionNo())
                            .header("Cache-Control", "no-cache")
                            .header("X-Accel-Buffering", "no")
                            .contentType(MediaType.TEXT_EVENT_STREAM)
                            .body(withPersistence(raw, prepared, req, userId));
                });
    }

    /**
     * 会话初始化 + 上下文准备。
     * 顺序很重要：先合并槽位，再读上下文，否则用户这轮补充的预算要等下一轮才生效。
     */
    private Mono<PreparedStream> prepareStream(ChatRequest req, long userId) {
        return sessionService.ensureSession(req.sessionId(), userId, req.message())
                .flatMap(ctx -> sessionService.mergeSlots(ctx.sessionId(), req.slots())
                        .then(sessionService.prepareContext(ctx.sessionId()))
                        .map(agentCtx -> new PreparedStream(
                                ctx.sessionNo(), ctx.sessionId(),
                                agentCtx.history(), agentCtx.slots())));
    }

    /**
     * 流式透传 + 顺手收集末态用于落库。
     * <p>
     * 关键：这里只挂 side-effect，绝不改变 Flux 的推送节奏。
     * 用 {@code doFinally} 而不是 {@code doOnComplete}，这样用户中途关页面、
     * 连接断开时回答也能落库——否则历史里会缺一轮。
     */
    private Flux<ServerSentEvent<String>> withPersistence(
            Flux<ServerSentEvent<String>> raw, PreparedStream prepared,
            ChatRequest req, long userId) {

        AtomicReference<StringBuilder> answerRef = new AtomicReference<>(new StringBuilder());
        AtomicReference<Integer> tokensRef = new AtomicReference<>(0);
        AtomicReference<Integer> latencyRef = new AtomicReference<>(0);

        return raw
                .doOnNext(evt -> collect(evt, answerRef, tokensRef, latencyRef))
                .doFinally(signal -> {
                    if (prepared.sessionId() <= 0) {
                        return; // 会话没建成功，不落库
                    }
                    String answer = answerRef.get().toString();
                    if (answer.isBlank()) {
                        return; // 空回答不落库，避免历史里出现空气泡
                    }
                    sessionService.persistTurn(
                                    prepared.sessionId(),
                                    req.message(),
                                    answer,
                                    null,
                                    null,
                                    tokensRef.get() == null ? 0 : tokensRef.get(),
                                    latencyRef.get() == null ? 0 : latencyRef.get(),
                                    null)
                            .subscribe(
                                    v -> { },
                                    e -> log.error("对话落库失败 sessionId={}", prepared.sessionNo(), e));
                });
    }

    /**
     * 从 SSE 事件里摘出落库所需信息。
     * 只读不改，事件本身照原样往下游走。
     * <p>
     * 为什么手写解析而不用 Jackson：token 事件每轮上百条，
     * 每条都建一次 ObjectMapper 调用不划算。这里只取两个字段，够用。
     */
    private void collect(ServerSentEvent<String> evt,
                         AtomicReference<StringBuilder> answer,
                         AtomicReference<Integer> tokens,
                         AtomicReference<Integer> latency) {
        String type = evt.event();
        String data = evt.data();
        if (type == null || data == null) {
            return;
        }
        try {
            if ("token".equals(type)) {
                String text = extractString(data, "text");
                if (text != null) {
                    answer.get().append(text);
                }
            } else if ("done".equals(type)) {
                Integer t = extractInt(data, "totalTokens");
                if (t != null) {
                    tokens.set(t);
                }
                Integer l = extractInt(data, "latencyMs");
                if (l != null) {
                    latency.set(l);
                }
            }
        } catch (RuntimeException e) {
            // 解析失败不能影响主流程 —— 落库是附加能力，流才是本体
            log.debug("SSE 事件解析失败 type={}", type);
        }
    }

    /** 取字符串字段值，处理 JSON 转义 */
    private static String extractString(String json, String key) {
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) {
            return null;
        }
        int colon = json.indexOf(':', i);
        if (colon < 0) {
            return null;
        }
        int start = json.indexOf('"', colon + 1);
        if (start < 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int p = start + 1; p < json.length(); p++) {
            char c = json.charAt(p);
            if (c == '\\' && p + 1 < json.length()) {
                char n = json.charAt(++p);
                switch (n) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        // JSON 的 unicode 转义：反斜杠 + u + 四位十六进制
                        if (p + 4 < json.length()) {
                            try {
                                sb.append((char) Integer.parseInt(json.substring(p + 1, p + 5), 16));
                                p += 4;
                            } catch (NumberFormatException ignored) {
                                // 转义坏了就原样保留，不让落库因为一个字符失败
                            }
                        }
                    }
                    default -> sb.append(n);
                }
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 取整数字段值 */
    private static Integer extractInt(String json, String key) {
        int i = json.indexOf("\"" + key + "\"");
        if (i < 0) {
            return null;
        }
        int colon = json.indexOf(':', i);
        if (colon < 0) {
            return null;
        }
        int p = colon + 1;
        while (p < json.length() && !Character.isDigit(json.charAt(p)) && json.charAt(p) != '-') {
            p++;
        }
        int start = p;
        while (p < json.length() && (Character.isDigit(json.charAt(p)) || json.charAt(p) == '-')) {
            p++;
        }
        if (p <= start) {
            return null;
        }
        try {
            return Integer.parseInt(json.substring(start, p));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 预处理结果：一次请求需要的全部上下文 */
    private record PreparedStream(String sessionNo, long sessionId,
                                  List<Map<String, String>> history,
                                  Map<String, Object> slots) {
        PreparedStream {
            if (slots == null) {
                slots = new LinkedHashMap<>();
            }
        }
    }
}

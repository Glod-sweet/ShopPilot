package com.shoppilot.chat;

import com.shoppilot.chat.dto.ChatRequest;
import com.shoppilot.chat.service.ChatSessionService;
import com.shoppilot.common.BizException;
import com.shoppilot.common.ErrorCode;
import com.shoppilot.common.TraceIdFilter;
import com.shoppilot.observability.service.ObservabilityService;
import com.shoppilot.security.AuthWebFilter;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 前端唯一入口（api-contract.md 第 3、4 节）。
 * <p>
 * 两条铁律：
 *   1. 必须是 WebFlux 的 Flux 直接返回，绝不能在中间 collectList()，
 *      否则流式会退化成「憋完整再吐」
 *   2. Java 只透传，不改写事件内容 —— 一旦在这里加工，三端契约就失效了
 * <p>
 * 落库放在流的终止信号里（doFinally），不在流中间写：
 * 中间每写一次库都会拖慢首 token 延迟，而完整回答要等流结束才知道。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final AgentClient agentClient;
    private final ChatSessionService sessionService;
    private final ObservabilityService observability;

    public ChatController(AgentClient agentClient,
                          ChatSessionService sessionService,
                          ObservabilityService observability) {
        this.agentClient = agentClient;
        this.sessionService = sessionService;
        this.observability = observability;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Mono<ResponseEntity<Flux<ServerSentEvent<String>>>> stream(
            @Valid @RequestBody ChatRequest req,
            ServerWebExchange exchange) {

        long userId = AuthWebFilter.currentUser(exchange).userId();
        String traceId = TraceIdFilter.current(exchange);

        return sessionService.ensureSession(req.sessionId(), userId, req.message())
                .flatMap(session -> sessionService.mergeSlots(session.sessionId(), req.slots())
                        .thenReturn(session))
                .map(session -> {
                    long started = System.currentTimeMillis();

                    // 累积流里出现的内容，用于结束后落库
                    StringBuilder text = new StringBuilder();
                    AtomicReference<String> eventType = new AtomicReference<>("token");
                    AtomicReference<String> payload = new AtomicReference<>(null);
                    AtomicInteger totalTokens = new AtomicInteger(0);

                    Flux<ServerSentEvent<String>> upstream = agentClient
                            .streamChat(userId, session.sessionId(), session.sessionNo(), req, traceId)
                            .doOnNext(evt -> accumulate(evt, text, eventType, payload, totalTokens));

                    Flux<ServerSentEvent<String>> withPersist = upstream
                            .doFinally(signal -> persistTurn(session.sessionId(), req.message(),
                                    text, eventType, payload, totalTokens,
                                    (int) (System.currentTimeMillis() - started),
                                    traceId, signal));

                    // 上游异常必须转成契约里的 error 事件，不能让连接直接断掉
                    Flux<ServerSentEvent<String>> safe = withPersist
                            .onErrorResume(ex -> {
                                log.error("对话流异常 sessionId={} traceId={}",
                                        session.sessionNo(), traceId, ex);
                                boolean timeout = ex instanceof java.util.concurrent.TimeoutException
                                        || (ex.getMessage() != null
                                        && ex.getMessage().toLowerCase().contains("timeout"));
                                ErrorCode code = timeout ? ErrorCode.AGENT_TIMEOUT : ErrorCode.AGENT_ERROR;
                                String data = """
                                        {"code":"%s","message":"%s","retryable":true}"""
                                        .formatted(code.name(), code.defaultMessage());
                                return Flux.just(ServerSentEvent.<String>builder()
                                        .event("error")
                                        .data(data)
                                        .build());
                            });

                    return ResponseEntity.ok()
                            .header("X-Session-Id", session.sessionNo())
                            .header("Cache-Control", "no-cache")
                            .header("X-Accel-Buffering", "no")
                            .contentType(MediaType.TEXT_EVENT_STREAM)
                            .body(safe);
                });
    }

    /** 从 SSE 事件里累积文本、事件类型、token 数，供落库用 */
    private void accumulate(ServerSentEvent<String> evt, StringBuilder text,
                            AtomicReference<String> eventType,
                            AtomicReference<String> payload,
                            AtomicInteger totalTokens) {
        String type = evt.event();
        String data = evt.data();
        if (type == null || data == null) {
            return;
        }
        switch (type) {
            case "token" -> {
                String t = extractField(data, "text");
                if (t != null) {
                    text.append(t);
                }
            }
            case "product_card", "slot_ask" -> {
                // 卡片与追问是结构化内容，整段 payload 存下来供历史回看渲染
                eventType.set(type);
                payload.set(data);
            }
            case "done" -> {
                String tokens = extractField(data, "totalTokens");
                if (tokens != null) {
                    try {
                        totalTokens.set(Integer.parseInt(tokens));
                    } catch (NumberFormatException ignored) {
                        // 上游给的 token 数格式异常不影响主流程
                    }
                }
            }
            default -> {
                // meta / tool_call / tool_result 只用于前端状态展示，不落库
            }
        }
    }

    /**
     * 极简 JSON 字段提取。刻意不引 ObjectMapper：
     * 这里只需要从扁平 JSON 里取一个字符串字段，引依赖反而增加序列化开销。
     */
    private static String extractField(String json, String field) {
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) {
            return null;
        }
        int colon = json.indexOf(':', idx + key.length());
        if (colon < 0) {
            return null;
        }
        int start = json.indexOf('"', colon + 1);
        if (start < 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = start + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                sb.append(json.charAt(++i));
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return null;
    }

    private void persistTurn(long sessionId, String userMessage, StringBuilder text,
                             AtomicReference<String> eventType, AtomicReference<String> payload,
                             AtomicInteger totalTokens, int latencyMs, String traceId,
                             SignalType signal) {
        // 只在上游正常完成时落库；取消/出错就丢弃，宁可少一轮也不要落半截
        if (signal != SignalType.ON_COMPLETE) {
            log.debug("流未正常结束 signal={}，跳过落库 sessionId={}", signal, sessionId);
            return;
        }
        String assistantText = text.toString();
        if (assistantText.isBlank() && payload.get() == null) {
            log.debug("流内容为空，跳过落库 sessionId={}", sessionId);
            return;
        }
        sessionService.persistTurn(sessionId, userMessage, assistantText,
                        eventType.get(), payload.get(), totalTokens.get(), latencyMs, traceId)
                .subscribe();
    }

    /** 会话列表（历史回看） */
    @GetMapping("/sessions")
    public Mono<Map<String, Object>> sessions(@RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "20") int size,
                                              ServerWebExchange exchange) {
        long userId = AuthWebFilter.currentUser(exchange).userId();
        return sessionService.listSessions(userId, page, size)
                .map(list -> ok(Map.of("sessions", list, "total", list.size())));
    }

    /** 会话详情：消息 + 槽位，前端刷新页面后恢复对话用 */
    @GetMapping("/sessions/{sessionNo}")
    public Mono<Map<String, Object>> sessionDetail(@PathVariable String sessionNo,
                                                   ServerWebExchange exchange) {
        long userId = AuthWebFilter.currentUser(exchange).userId();
        return sessionService.sessionDetail(sessionNo, userId)
                .switchIfEmpty(Mono.error(BizException.notFound("会话")))
                .map(this::ok);
    }

    /**
     * 前端上报「用户点了哪些推荐商品」。
     * 这是算 CTR 的唯一数据来源，没有它 handbook 4.5 的效果评估就是空的。
     */
    @PostMapping("/click")
    public Mono<Map<String, Object>> reportClick(@RequestBody Map<String, Object> body,
                                                 ServerWebExchange exchange) {
        // 鉴权只为确保是登录用户，埋点本身不区分用户
        AuthWebFilter.currentUser(exchange);
        String traceId = str(body.get("traceId"));
        List<Long> productIds = toLongList(body.get("productIds"));
        if (traceId == null || productIds.isEmpty()) {
            return Mono.just(Map.of("code", 1001, "message", "traceId 与 productIds 必填"));
        }
        return observability.markClicked(traceId, productIds)
                .map(rows -> ok(Map.of("updated", rows)));
    }

    /** 效果统计（CTR / CVR / 平均时延），直接查数据库视图 */
    @GetMapping("/stats")
    public Mono<Map<String, Object>> stats(@RequestParam(defaultValue = "7") int days,
                                           ServerWebExchange exchange) {
        AuthWebFilter.currentUser(exchange);
        // dailyStats 返回的已是 Mono<List<...>>，不能再 collectList()
        return observability.dailyStats(days)
                .map(list -> ok(Map.of("daily", list)));
    }

    private Map<String, Object> ok(Object data) {
        return Map.of("code", 0, "message", "ok", "data", data);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static List<Long> toLongList(Object o) {
        if (o instanceof List<?> list) {
            return list.stream()
                    .filter(v -> v instanceof Number)
                    .map(v -> ((Number) v).longValue())
                    .toList();
        }
        return List.of();
    }
}

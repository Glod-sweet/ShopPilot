package com.shoppilot.observability.service;

import com.shoppilot.common.JsonUtils;
import com.shoppilot.repository.ObservabilityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 埋点服务（handbook 4.5）。
 * <p>
 * 埋点绝不能影响主链路：任何写入失败都只记日志、不向上抛。
 * 这是有意的取舍——推荐日志丢一条无所谓，用户对话中断不可接受。
 */
@Service
public class ObservabilityService {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityService.class);

    private final ObservabilityRepository repo;

    public ObservabilityService(ObservabilityRepository repo) {
        this.repo = repo;
    }

    /** 记录一次推荐：召回了什么、展示了什么、耗时多少 */
    public void logRecommend(String traceId, Long sessionId, Long userId, String query,
                             List<Long> recalledIds, List<Long> filteredIds,
                             List<Long> shownIds, int latencyMs) {
        repo.insertRecommendLog(traceId, sessionId, userId, query,
                        JsonUtils.write(recalledIds), JsonUtils.write(filteredIds),
                        JsonUtils.write(shownIds),
                        recalledIds == null ? 0 : recalledIds.size(),
                        shownIds == null ? 0 : shownIds.size(),
                        latencyMs)
                .doOnError(e -> log.warn("推荐埋点写入失败 traceId={}", traceId, e))
                .onErrorResume(e -> Mono.empty())
                .subscribe();
    }

    /** 记录一次工具调用，排查 Agent 行为用 */
    public void logToolCall(String traceId, Long sessionId, String toolName,
                            Map<String, Object> args, int resultCode, int elapsedMs) {
        repo.insertToolCallLog(traceId, sessionId, toolName,
                        JsonUtils.write(args), resultCode, elapsedMs)
                .doOnError(e -> log.warn("工具埋点写入失败 traceId={} tool={}", traceId, toolName, e))
                .onErrorResume(e -> Mono.empty())
                .subscribe();
    }

    /** 用户点击了推荐商品（前端上报），用于算 CTR */
    public Mono<Long> markClicked(String traceId, List<Long> productIds) {
        return repo.appendClicked(traceId, JsonUtils.write(productIds))
                .onErrorResume(e -> {
                    log.warn("点击埋点失败 traceId={}", traceId, e);
                    return Mono.just(0L);
                });
    }

    /** 效果日报：点击率 / 转化率 / 平均时延 */
    public Mono<List<Map<String, Object>>> dailyStats(int days) {
        return repo.dailyStats(Math.min(Math.max(days, 1), 90)).collectList();
    }
}

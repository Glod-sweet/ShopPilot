package com.shoppilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 向量模型配置（OpenAI 兼容协议，默认阿里云百炼）。
 * <p>
 * 为什么放在 Java 侧而不是让 Python 算好传过来：
 * 检索是数据层职责。Java 是唯一有状态层，商城前台的搜索框、后台的相似商品推荐
 * 也都要用向量，把 embedding 能力放在 Python 里会导致「换个入口就要复制一套调用」。
 * 更关键的是——如果 Python 负责算 query 向量，等于 Python 参与了检索决策，
 * 违反了 handbook「数据归 Java，判断归 Python」的分界线。
 * <p>
 * 未配置 api-key 时自动降级为关键词打分（见 HybridSearchService），
 * 本地开发不配也能跑，只是检索质量差一些。
 */
@ConfigurationProperties(prefix = "pilot.embedding")
public record EmbeddingProperties(
        String baseUrl,
        String apiKey,
        String model,
        int dim,
        /** 单次向量化超时。检索链路对延迟敏感，超过就直接降级而不是拖死请求 */
        int timeoutMs
) {

    /** 是否具备调用条件。缺 key 就不调，省一次必然失败的往返 */
    public boolean enabled() {
        return apiKey != null && !apiKey.isBlank() && baseUrl != null && !baseUrl.isBlank();
    }
}

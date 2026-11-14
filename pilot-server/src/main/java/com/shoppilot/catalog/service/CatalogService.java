package com.shoppilot.catalog.service;

import com.shoppilot.catalog.dto.CategoryNode;
import com.shoppilot.catalog.dto.ProductCard;
import com.shoppilot.catalog.dto.ProductDetail;
import com.shoppilot.catalog.dto.ProductQuery;
import com.shoppilot.common.BizException;
import com.shoppilot.common.JsonUtils;
import com.shoppilot.common.PageResult;
import com.shoppilot.common.RowValues;
import com.shoppilot.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 商品目录服务：列表、详情、品类树、品牌。
 * <p>
 * 与 {@link com.shoppilot.search.HybridSearchService} 的分工：
 * 这里是「人找货」的浏览路径（分类筛选、排序、分页），
 * 那里是「货找人」的 Agent 检索路径（场景识别 + 重排序）。
 * 两者共用 ProductRepository，但不共用排序逻辑——浏览要可预测，检索要智能。
 */
@Service
public class CatalogService {

    private static final Logger log = LoggerFactory.getLogger(CatalogService.class);

    private final ProductRepository productRepo;

    public CatalogService(ProductRepository productRepo) {
        this.productRepo = productRepo;
    }

    /** 商品分页列表 */
    public Mono<PageResult<ProductCard>> page(ProductQuery q) {
        log.debug("商品列表查询 {}", q.fingerprint());
        Mono<List<ProductCard>> list = productRepo
                .pageProducts(q.categoryId(), q.brandId(), q.minPrice(), q.maxPrice(),
                        q.keyword(), q.orderByClause(), q.offset(), q.size(), q.includeChildren())
                .map(ProductCard::from)
                .collectList();

        Mono<Long> total = productRepo.countProducts(q.categoryId(), q.brandId(),
                q.minPrice(), q.maxPrice(), q.keyword(), q.includeChildren());

        return Mono.zip(list, total)
                .map(t -> PageResult.of(t.getT1(), t.getT2(), q.page(), q.size()));
    }

    /**
     * 商品详情。三张表并行查：主表、SKU、场景。
     * 用 zip 而不是串行，省两次 RTT；任一路为空都不影响主表结果（SKU 可能没有）。
     */
    public Mono<ProductDetail> detail(long productId) {
        Mono<Map<String, Object>> base = productRepo.findById(productId)
                .switchIfEmpty(Mono.error(BizException.notFound("商品")));
        Mono<List<Map<String, Object>>> skus = productRepo.findSkusByProductId(productId).collectList();
        Mono<List<Map<String, Object>>> scenes = productRepo.findScenesByProductId(productId).collectList();

        return Mono.zip(base, skus, scenes).map(t -> assemble(t.getT1(), t.getT2(), t.getT3()));
    }

    private ProductDetail assemble(Map<String, Object> p,
                                   List<Map<String, Object>> skuRows,
                                   List<Map<String, Object>> sceneRows) {
        List<ProductDetail.Sku> skus = skuRows.stream()
                .map(s -> new ProductDetail.Sku(
                        toLong(s.get("id")),
                        str(s.get("sku_code")),
                        JsonUtils.toMap(str(s.get("spec"))),
                        toDecimal(s.get("price")),
                        toInt(s.get("stock")),
                        str(s.get("image_url")),
                        toInt(s.get("status")) != null && toInt(s.get("status")) == 1
                                && toInt(s.get("stock")) != null && toInt(s.get("stock")) > 0))
                .toList();

        List<ProductDetail.SceneRef> scenes = sceneRows.stream()
                .map(s -> new ProductDetail.SceneRef(
                        toLong(s.get("scene_id")),
                        str(s.get("scene_code")),
                        str(s.get("scene_name")),
                        toDecimal(s.get("score")),
                        str(s.get("reason"))))
                .toList();

        return new ProductDetail(
                toLong(p.get("id")),
                str(p.get("product_no")),
                str(p.get("title")),
                str(p.get("sub_title")),
                toDecimal(p.get("price")),
                toDecimal(p.get("original_price")),
                str(p.get("cover_url")),
                JsonUtils.toStringList(str(p.get("album"))),
                JsonUtils.toStringList(str(p.get("tags"))),
                str(p.get("selling_points")),
                str(p.get("description")),
                JsonUtils.toMap(str(p.get("attributes"))),
                toInt(p.get("stock")),
                toInt(p.get("sales_count")),
                toDecimal(p.get("rating")),
                toInt(p.get("comment_count")),
                toInt(p.get("on_shelf")) != null && toInt(p.get("on_shelf")) == 1,
                toInt(p.get("gift_wrappable")) != null && toInt(p.get("gift_wrappable")) == 1,
                toLong(p.get("category_id")),
                str(p.get("category_name")),
                toLong(p.get("brand_id")),
                str(p.get("brand_name")),
                skus,
                scenes);
    }

    /** 品类树：一次查全表在内存里组树，品类表很小，不值得递归查库 */
    public Mono<List<CategoryNode>> categoryTree() {
        return productRepo.findAllCategories()
                .collectList()
                .map(rows -> {
                    Map<Long, List<CategoryNode>> byParent = new LinkedHashMap<>();
                    List<Map<String, Object>> parents = new ArrayList<>();

                    for (Map<String, Object> r : rows) {
                        long parentId = toLong(r.get("parent_id")) == null ? 0L : toLong(r.get("parent_id"));
                        if (parentId == 0L) {
                            parents.add(r);
                        } else {
                            byParent.computeIfAbsent(parentId, k -> new ArrayList<>())
                                    .add(new CategoryNode(toLong(r.get("id")), str(r.get("name")),
                                            parentId, toInt(r.get("level")), toInt(r.get("sort")), List.of()));
                        }
                    }

                    return parents.stream()
                            .map(r -> new CategoryNode(
                                    toLong(r.get("id")), str(r.get("name")), 0L,
                                    toInt(r.get("level")), toInt(r.get("sort")),
                                    byParent.getOrDefault(toLong(r.get("id")), List.of())))
                            .collect(Collectors.toList());
                });
    }

    public Mono<List<Map<String, Object>>> brands() {
        return productRepo.findAllBrands()
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("brandId", toLong(r.get("id")));
                    m.put("name", str(r.get("name")));
                    m.put("logoUrl", str(r.get("logo_url")));
                    m.put("description", str(r.get("description")));
                    m.put("productCount", toInt(r.get("product_count")));
                    return m;
                })
                .collectList();
    }

    /** 全部场景，前端可做「按场景逛」入口 */
    public Mono<List<Map<String, Object>>> scenes() {
        return productRepo.findAllScenes()
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("sceneId", toLong(r.get("id")));
                    m.put("code", str(r.get("code")));
                    m.put("name", str(r.get("name")));
                    m.put("keywords", JsonUtils.toStringList(str(r.get("keywords"))));
                    m.put("budgetHintMin", toDecimal(r.get("budget_hint_min")));
                    m.put("budgetHintMax", toDecimal(r.get("budget_hint_max")));
                    return m;
                })
                .collectList();
    }

    private static Long toLong(Object o) {
        return RowValues.asLong(o);
    }

    private static Integer toInt(Object o) {
        return RowValues.asIntOrNull(o);
    }

    private static BigDecimal toDecimal(Object o) {
        return RowValues.asDecimal(o);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}

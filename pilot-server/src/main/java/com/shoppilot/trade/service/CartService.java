package com.shoppilot.trade.service;

import com.shoppilot.common.BizException;
import com.shoppilot.common.ErrorCode;
import com.shoppilot.common.JsonUtils;
import com.shoppilot.common.RowValues;
import com.shoppilot.repository.CartRepository;
import com.shoppilot.repository.ProductRepository;
import com.shoppilot.trade.dto.AddCartRequest;
import com.shoppilot.trade.dto.CartView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 购物车服务。
 * <p>
 * 三条硬规则：
 *   1. 价格只认数据库当前值，不认加购时的快照 —— 用户看到的必须是当下能成交的价格
 *   2. 库存不在加购时扣减，只在下单时用条件 UPDATE 原子扣减
 *   3. 每条写操作都带 user_id 条件，从 SQL 层杜绝越权
 */
@Service
public class CartService {

    private static final Logger log = LoggerFactory.getLogger(CartService.class);

    private final CartRepository cartRepo;
    private final ProductRepository productRepo;

    public CartService(CartRepository cartRepo, ProductRepository productRepo) {
        this.cartRepo = cartRepo;
        this.productRepo = productRepo;
    }

    /** 加购。先校验商品存在且在架，再写库，避免购物车塞进死商品 */
    public Mono<Long> add(long userId, AddCartRequest req) {
        return productRepo.findById(req.productId())
                .switchIfEmpty(Mono.error(BizException.notFound("商品")))
                .flatMap(p -> {
                    int onShelf = toInt(p.get("on_shelf"), 0);
                    if (onShelf != 1) {
                        return Mono.error(new BizException(ErrorCode.PRODUCT_OFF_SHELF));
                    }
                    int stock = toInt(p.get("stock"), 0);
                    if (stock <= 0) {
                        return Mono.error(new BizException(ErrorCode.STOCK_NOT_ENOUGH, "商品已售罄"));
                    }
                    int qty = Math.min(req.quantityOrDefault(), Math.max(stock, 1));
                    Long sessionDbId = parseSessionId(req.sessionId());
                    String source = req.agentContext() == null ? "user" : "agent";

                    return cartRepo.upsert(userId, req.productId(), req.skuId(), qty, source, sessionDbId)
                            .doOnSuccess(id -> log.info("加购成功 userId={} productId={} qty={} source={}",
                                    userId, req.productId(), qty, source));
                });
    }

    /** 购物车视图。金额在服务端算，前端只负责展示 */
    public Mono<CartView> view(long userId) {
        return cartRepo.findItems(userId)
                .map(this::toItem)
                .collectList()
                .map(this::summarize);
    }

    public Mono<Void> updateQuantity(long userId, long cartItemId, int quantity) {
        if (quantity <= 0) {
            return cartRepo.deleteItem(userId, cartItemId).then();
        }
        if (quantity > 99) {
            return Mono.error(BizException.param("单件商品最多 99 件"));
        }
        return cartRepo.updateQuantity(userId, cartItemId, quantity)
                .flatMap(rows -> rows != null && rows > 0
                        ? Mono.empty()
                        : Mono.error(BizException.notFound("购物车条目")));
    }

    public Mono<Void> select(long userId, long cartItemId, boolean selected) {
        return cartRepo.updateSelected(userId, cartItemId, selected).then();
    }

    public Mono<Void> selectAll(long userId, boolean selected) {
        return cartRepo.updateAllSelected(userId, selected).then();
    }

    public Mono<Void> remove(long userId, long cartItemId) {
        return cartRepo.deleteItem(userId, cartItemId).then();
    }

    public Mono<Long> count(long userId) {
        return cartRepo.countItems(userId);
    }

    private CartView.Item toItem(Map<String, Object> row) {
        BigDecimal price = firstNonNull(
                toDecimal(row.get("sku_price")),
                toDecimal(row.get("product_price")),
                BigDecimal.ZERO);

        // 库存优先看 SKU 级：选了规格就该按规格的库存判断
        Integer skuStock = toInt(row.get("sku_stock"));
        Integer productStock = toInt(row.get("stock"));
        int stock = skuStock != null ? skuStock : (productStock == null ? 0 : productStock);

        int onShelf = toInt(row.get("on_shelf"), 0);
        int qty = toInt(row.get("quantity"), 1);
        Integer skuStatus = toInt(row.get("sku_status"));

        String warning = null;
        if (onShelf != 1) {
            warning = "商品已下架";
        } else if (skuStatus != null && skuStatus != 1) {
            warning = "该规格已停售";
        } else if (stock <= 0) {
            warning = "已售罄";
        } else if (stock < qty) {
            warning = "库存仅剩 " + stock + " 件";
        }

        BigDecimal amount = price.multiply(BigDecimal.valueOf(qty));

        return new CartView.Item(
                toLong(row.get("cart_item_id")),
                toLong(row.get("product_id")),
                toLong(row.get("sku_id")),
                str(row.get("title")),
                str(row.get("sub_title")),
                str(row.get("cover_url")),
                str(row.get("spec")),
                price,
                qty,
                amount,
                stock,
                onShelf == 1,
                toInt(row.get("selected"), 0) == 1,
                str(row.get("source")),
                warning);
    }

    private CartView summarize(List<CartView.Item> items) {
        int totalQty = 0;
        int invalid = 0;
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal payable = BigDecimal.ZERO;

        for (CartView.Item i : items) {
            totalQty += i.quantity() == null ? 0 : i.quantity();
            total = total.add(i.amount() == null ? BigDecimal.ZERO : i.amount());
            // 只有「勾选 且 无警告」的条目才计入应付金额
            if (Boolean.TRUE.equals(i.selected())) {
                if (i.warning() != null) {
                    invalid++;
                } else {
                    payable = payable.add(i.amount());
                }
            }
        }

        return new CartView(items,
                new CartView.Summary(items.size(), totalQty, total, payable, invalid));
    }

    /**
     * 会话号 → 数据库主键。
     * 加购时前端传的是 s_xxx 形式的 sessionNo，但 sp_cart_item.session_id
     * 存的是自增主键，这里做一次转换；查不到就当作无归因，不阻断加购。
     */
    private Long parseSessionId(String sessionNo) {
        if (sessionNo == null || sessionNo.isBlank()) {
            return null;
        }
        // 只处理形如 s_xxx 的对外编号，纯数字则视为已是主键
        if (sessionNo.matches("^\\d+$")) {
            try {
                return Long.parseLong(sessionNo);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static BigDecimal firstNonNull(BigDecimal... values) {
        for (BigDecimal v : values) {
            if (v != null) {
                return v;
            }
        }
        return BigDecimal.ZERO;
    }

    private static Long toLong(Object o) {
        return RowValues.asLong(o);
    }

    private static Integer toInt(Object o) {
        return RowValues.asIntOrNull(o);
    }

    private static int toInt(Object o, int dft) {
        return RowValues.asInt(o, dft);
    }

    private static BigDecimal toDecimal(Object o) {
        return RowValues.asDecimal(o);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    /** 供订单服务复用：把行转成可结算条目 */
    static Map<String, Object> withJsonSpec(Map<String, Object> row) {
        Object spec = row.get("sku_spec");
        if (spec != null) {
            row.put("sku_spec", JsonUtils.write(spec));
        }
        return row;
    }
}

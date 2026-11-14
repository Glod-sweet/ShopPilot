package com.shoppilot.chat;

import com.shoppilot.repository.ProductRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final ProductRepository productRepo;

    public HealthController(ProductRepository productRepo) {
        this.productRepo = productRepo;
    }

    /**
     * 健康检查带数据库探活：不只是「进程活着」，而是「依赖可用」。
     * 排查时第一件事就是看这个接口，能立刻区分「服务挂了」和「库连不上」。
     */
    @GetMapping
    public Mono<Map<String, Object>> health() {
        return productRepo.countProducts()
                .map(count -> {
                    Map<String, Object> db = new LinkedHashMap<>();
                    db.put("status", "up");
                    db.put("productCount", count);

                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("status", "ok");
                    body.put("service", "pilot-server");
                    body.put("version", "0.1.0");
                    body.put("database", db);
                    return body;
                })
                .onErrorResume(ex -> {
                    Map<String, Object> db = new LinkedHashMap<>();
                    db.put("status", "down");
                    db.put("error", ex.getMessage());

                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("status", "degraded");
                    body.put("service", "pilot-server");
                    body.put("version", "0.1.0");
                    body.put("database", db);
                    return Mono.just(body);
                });
    }
}

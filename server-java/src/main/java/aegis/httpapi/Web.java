package aegis.httpapi;

import aegis.domain.Models.AppError;
import aegis.platform.Support;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * 跨域和统一错误响应。前端仍按 Go 版的 error 对象解析。
 */
@Configuration
class Web {
    /**
     * 允许浏览器跨域调用 /api，并带上幂等键请求头。
     *
     * @return 跨域过滤器
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    CorsFilter corsFilter() {
        CorsConfiguration cors = new CorsConfiguration();
        cors.addAllowedOriginPattern("*");
        cors.addAllowedHeader("Content-Type");
        cors.addAllowedHeader("Idempotency-Key");
        cors.addAllowedMethod("GET");
        cors.addAllowedMethod("POST");
        cors.addAllowedMethod("OPTIONS");
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return new CorsFilter(source);
    }

    /**
     * 记录每个请求的方法、路径、状态码和耗时。
     *
     * @return 访问日志过滤器
     */
    @Bean
    OncePerRequestFilter accessLog() {
        return new OncePerRequestFilter() {
            /**
             * 放行请求并在结束时打印一行访问日志。
             *
             * @param request 请求
             * @param response 响应
             * @param chain 后续过滤器
             */
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
                long start = System.nanoTime();
                try {
                    chain.doFilter(request, response);
                } finally {
                    System.out.printf("http method=%s path=%s status=%d ms=%d%n", request.getMethod(), request.getRequestURI(), response.getStatus(), (System.nanoTime() - start) / 1_000_000);
                }
            }
        };
    }
}

/**
 * 把业务异常和非法 JSON 转成统一错误体。
 */
@RestControllerAdvice
class Errors {
    /**
     * 业务异常。
     *
     * @param e 业务异常
     * @return 带状态码的错误体
     */
    @ExceptionHandler(AppError.class)
    ResponseEntity<Map<String, Object>> app(AppError e) {
        return ResponseEntity.status(Support.status(e)).body(Map.of("error", e.body()));
    }

    /**
     * 没有匹配的接口。
     *
     * @return 404
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    ResponseEntity<Map<String, Object>> missing() {
        return ResponseEntity.status(404).body(Map.of("error", AppError.of("NOT_FOUND", "没有这个接口", "").body()));
    }

    /**
     * 请求体不是合法 JSON。
     *
     * @return 400
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> badJson() {
        return ResponseEntity.status(400).body(Map.of("error", AppError.of("INVALID_ARGUMENT", "请求体不是合法 JSON", "").body()));
    }

    /**
     * 其它未分类异常，按链错误说明转成 502 或对应状态码。
     *
     * @param e 异常
     * @return 错误体
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> other(Exception e) {
        AppError explained = Support.explain(e);
        return ResponseEntity.status(Support.status(explained)).body(Map.of("error", explained.body()));
    }
}

package com.yuyue.config;

import com.yuyue.web.AccessLogInterceptor;
import com.yuyue.web.AuthInterceptor;
import com.yuyue.web.VisitLogInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.lang.NonNull;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final AccessLogInterceptor accessLogInterceptor;
    private final VisitLogInterceptor visitLogInterceptor;
    private final UploadProperties uploadProperties;

    /** 访问日志统一排除：静态资源 / 文档 / 错误页，避免无意义噪音 */
    private static final String[] LOG_EXCLUDES = {
            "/uploads/**",
            "/error",
            "/actuator/**",
            "/favicon.ico",
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/swagger-resources/**",
            "/webjars/**"
    };

    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {
        // 鉴权拦截器先执行（order 0），访问日志拦截器后注册（order 1）：
        // afterCompletion 按注册逆序回调，访问日志先输出、鉴权拦截器最后清理 UserContext
        registry.addInterceptor(authInterceptor)
                .order(0)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        // 只放行这几个"换 token"的入口；/auth/me、/auth/profile、/auth/logout
                        // 必须走拦截器，否则 UserContext 不会被填充，必然报未登录
                        "/auth/register",
                        "/auth/login",
                        "/auth/wxlogin",
                        // 图形验证码：登录前就要能拿（否则死循环了）
                        "/auth/captcha",
                        // 昵称查重：注册/编辑资料前预先校验（未登录）
                        "/auth/name-check",
                        // 个人头像静态资源：公开访问
                        "/uploads/**",
                        "/error",
                        "/actuator/**",
                        // ELO 试算：纯计算工具，无需登录（不读库不写库）
                        "/elo/**",
                        // 轮排试算公开；球局轮排需登录（/rotation/game/** 不再放行）
                        "/rotation/plan",
                        // Swagger / OpenAPI 文档资源必须放行，否则会被登录拦截
                        "/swagger-ui.html",
                        "/swagger-ui/**",
                        "/v3/api-docs",
                        "/v3/api-docs/**",
                        "/swagger-resources/**",
                        "/webjars/**"
                );

        // 全量访问日志：在鉴权之后执行，能读到 UserContext；静态资源 / 文档不记录
        registry.addInterceptor(accessLogInterceptor)
                .order(1)
                .addPathPatterns("/**")
                .excludePathPatterns(LOG_EXCLUDES);

        // 浏览 IP 记录：preHandle 时 AuthInterceptor 已填充 UserContext，
        // 只记 GET 浏览行为，异步落库；排除项与访问日志保持一致
        registry.addInterceptor(visitLogInterceptor)
                .order(2)
                .addPathPatterns("/**")
                .excludePathPatterns(LOG_EXCLUDES);
    }

    // 个人头像静态资源映射：/uploads/** → 本地上传目录
    @Override
    public void addResourceHandlers(@NonNull ResourceHandlerRegistry registry) {
        String dir = uploadProperties.getDir();
        String location = dir.endsWith("/") ? "file:" + dir : "file:" + dir + "/";
        registry.addResourceHandler(uploadProperties.getUrlPrefix() + "/**")
                .addResourceLocations(location);
    }

    // 跨域统一由 CorsConfig 的 CorsFilter 处理（含预检 OPTIONS），
    // 这里不再配 addCorsMappings，避免同一条响应上出现重复的 CORS 响应头。
}

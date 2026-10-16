package com.yuyue.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger / OpenAPI 3 文档配置
 * <p>
 * UI 地址：http://localhost:8080/api/swagger-ui.html
 * JSON 地址：http://localhost:8080/api/v3/api-docs
 */
@Configuration
public class OpenApiConfig {

    /** 与 AuthInterceptor 读取的请求头一致：Authorization: Bearer &lt;token&gt; */
    private static final String SECURITY_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI yuyueOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("数科羽 shukeyu - 校园羽毛球球局系统 API")
                        .description("""
                                球局发布 / 匿名报名 / 自动编排 / 对局上报 / ELO 积分结算 / 积分榜。

                                除 /auth/** 外所有接口都需要登录态：
                                先调用 POST /auth/login 拿到 token，再点右上角 Authorize 填入，
                                Swagger UI 会自动以 "Bearer <token>" 形式加到请求头。
                                """)
                        .version("1.0.0"))
                .components(new Components().addSecuritySchemes(SECURITY_SCHEME,
                        new SecurityScheme()
                                .name(SECURITY_SCHEME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("登录接口返回的 JWT，粘贴时不需要带 Bearer 前缀")))
                // 全局默认要求认证，/auth/** 下两个免登录接口在方法上用 @SecurityRequirements 单独放开
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME));
    }
}

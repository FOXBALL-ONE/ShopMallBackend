package top.foxball.shopmall.config

import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import top.foxball.shopmall.authentication.JwtAuthenticationFilter
import top.foxball.shopmall.authentication.JwtService
import top.foxball.shopmall.ratelimit.ApiRateLimitFilter
import top.foxball.shopmall.ratelimit.ApiRateLimitService
import top.foxball.shopmall.ratelimit.ClientIpResolver
import top.foxball.shopmall.ratelimit.RateLimitMetrics
import top.foxball.shopmall.ratelimit.RateLimitProperties
import top.foxball.shopmall.ratelimit.RateLimitSettingsService
import tools.jackson.databind.ObjectMapper

/**
 * Spring Security 配置：无状态 JWT（双 Token 模型）。
 *
 * 鉴权分级（authorizeHttpRequests 按声明顺序首匹配）：
 *  - 登录 / 续期 / 登出 / 注册 / 邮箱验证码 / 找回密码 / 邮箱验证登录：不要求 JWT（匿名流程；
 *    续期/登出凭 HttpOnly Cookie 里的 refresh，非 Bearer，access 过期也要能续期与登出）。
 *  - 公开查询 GET（商品 / 标签 / 评价等）：匿名可访问。
 *  - 公开投稿 POST：匿名提交，后端固定 PENDING 待审。
 *  - 项目方自服务（admin/project 前缀）：凭 controlPassword 鉴权，非 JWT，permitAll（必须在
 *    admin 前缀的 hasRole 规则之前，否则会被误拦）。
 *  - 其余管理端（admin 前缀）：凭角色 `ADMIN`（access 的 role claim → `ROLE_ADMIN`）。
 *  - 其余接口：需 JWT。
 *
 * 类型隔离 + 角色映射详见 `docs/dual-token-auth-design.md` §5.1 / §5.2。
 * 401/403 均回统一 JSON（与 shared.Response 一致）。
 */
@Configuration
class SecurityConfig(
    private val jwtService: JwtService,
    private val devTokenManager: DevTokenManager,
    private val rateLimitProperties: RateLimitProperties,
    private val rateLimitSettingsService: RateLimitSettingsService,
    private val apiRateLimitService: ApiRateLimitService,
    private val clientIpResolver: ClientIpResolver,
    private val objectMapper: ObjectMapper,
    private val rateLimitMetrics: RateLimitMetrics,
) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        val jwtAuthenticationFilter = JwtAuthenticationFilter(jwtService, devTokenManager)
        http
            .csrf { it.disable() }   // 无状态 JWT，CSRF 关闭；refresh cookie 靠 SameSite 防 CSRF（§6.3）
            .cors(Customizer.withDefaults())
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                // 登录 / 续期 / 登出 / 注册 / 邮箱验证码 / 找回密码 / 邮箱验证登录：不要求 JWT
                // （refresh 走 HttpOnly Cookie，续期/登出为匿名流程，access 过期也要能调用）
                it.requestMatchers(
                    "/api/auth/login",
                    "/api/auth/login/email",
                    "/api/auth/refresh",
                    "/api/auth/logout",
                    "/api/auth/register/manager",
                    "/api/auth/verification-code",
                    "/api/auth/reset-password",
                    "/api/users/Register",
                    "/error",
                ).permitAll()
                // 公开查询（GET）：商品 / 标签 / 评价等，匿名可访问
                it.requestMatchers(HttpMethod.GET, "/api/project/**").permitAll()
                it.requestMatchers(
                    HttpMethod.GET,
                    "/api/products/**",
                    "/api/product-types/**",
                    "/api/product-categories/**",
                    "/api/product-images/**",
                    "/api/tags/**",
                    "/api/customer-reviews/**",
                    "/api/announcements/**",
                    "/api/home/recommendations",
                ).permitAll()
                // 公开投稿（POST）：新项目 / 想法 / 评论 / 加入申请，匿名提交（后端固定 PENDING 待审）
                it.requestMatchers(
                    HttpMethod.POST,
                    "/api/project/object-items",
                    "/api/project/object-items/*/comments",
                    "/api/project/object-items/*/join-applications",
                    "/api/project/minds",
                ).permitAll()
                // 【顺序敏感】项目方自服务：凭 controlPassword 鉴权（非 JWT），必须在 /admin/api/** hasRole 之前放行，
                // 否则 hasRole('ADMIN') 会把它误拦（现状即此 permitAll）。
                it.requestMatchers("/admin/api/project/**").permitAll()
                it.requestMatchers(HttpMethod.POST, "/webhook").permitAll()
                it.requestMatchers(HttpMethod.POST, "/api/logistics/webhook/**").permitAll()
                it.requestMatchers(HttpMethod.GET, "/api/orders/*/shipments/**").authenticated()
                // 其余管理端：凭角色（access 的 role claim 映射为 ROLE_ADMIN）
                it.requestMatchers("/admin/api/**").hasRole("ADMIN")
                // 客户工单入口仅允许普通客户，避免管理员身份被记录为客户发送者。
                it.requestMatchers("/api/support-tickets/**").hasRole("CUSTOMER")
                // 仅带签名的下载 URL 可匿名访问，其余文件接口均要求 JWT。
                it.requestMatchers(HttpMethod.GET, "/api/files/*/download").permitAll()
                it.requestMatchers("/api/files/**").authenticated()
                it.requestMatchers("/actuator/health", "/actuator/info").permitAll()
                // 其余接口需 JWT：管理端写入、auth/me、auth/change-password、auth/password-code，
                // 以及 /api/project/** 下的写入（PUT/DELETE/batch）等
                it.anyRequest().authenticated()
            }
            .exceptionHandling {
                // 无/失效 JWT 的受保护接口统一回 401 JSON
                it.authenticationEntryPoint { _, response, _ ->
                    writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized")
                }
                // hasRole 拒绝时统一回 403 JSON（否则落到 Spring Security 默认 HTML）
                it.accessDeniedHandler { _, response, _ ->
                    writeJson(response, HttpServletResponse.SC_FORBIDDEN, "Forbidden")
                }
            }
            // 在标准账号密码过滤器前插入 JWT 过滤器：解析 Bearer 令牌并写入 SecurityContext
            .addFilterBefore(
                jwtAuthenticationFilter,
                UsernamePasswordAuthenticationFilter::class.java,
            )
        if (rateLimitProperties.filterEnabled) {
            http.addFilterAfter(
                ApiRateLimitFilter(
                    settingsService = rateLimitSettingsService,
                    rateLimitService = apiRateLimitService,
                    clientIpResolver = clientIpResolver,
                    objectMapper = objectMapper,
                    metrics = rateLimitMetrics,
                ),
                JwtAuthenticationFilter::class.java,
            )
        }
        return http.build()
    }

    private fun writeJson(response: HttpServletResponse, status: Int, message: String) {
        response.contentType = "application/json;charset=UTF-8"
        response.status = status
        response.writer.write("""{"status":$status,"message":"$message","data":{}}""")
    }

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val config = CorsConfiguration().apply {
            // origin 走回显：allowedOriginPatterns 支持带凭证，浏览器会收到具体 origin（而非字面 *）
            allowedOriginPatterns = listOf("*")
            // 方法 / 请求头必须显式列举：allowCredentials=true 时，浏览器不接受通配 *，
            // 否则 preflight 会以 "field content-type is not allowed by Access-Control-Allow-Headers" 拦截
            allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            allowedHeaders = listOf(
                "Authorization",
                "Content-Type",
                "Accept",
                "X-Requested-With",
                "Idempotency-Key",
            )
            allowCredentials = true
            exposedHeaders = listOf("Retry-After", "X-RateLimit-Limit", "X-RateLimit-Remaining")
            maxAge = 3600
        }
        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", config)
        return source
    }
}

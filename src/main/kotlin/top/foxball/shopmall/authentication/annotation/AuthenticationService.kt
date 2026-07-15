package top.foxball.shopmall.authentication.annotation

/** 标记认证领域服务，便于在需要时按认证职责进行组件筛选或切面处理。 */
@Target(AnnotationTarget.CLASS)
annotation class AuthenticationService

package top.foxball.shopmall.controller.admin

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import top.foxball.shopmall.service.AdminAccessService
import top.foxball.shopmall.service.UserService
import top.foxball.shopmall.shared.Response
import top.foxball.shopmall.shared.ResponseBuilder

/**
 * @folder 管理端/会话
 */
@RestController
@RequestMapping("/admin/api/session")
class AdminSessionController(
    private val userService: UserService,
    private val adminAccessService: AdminAccessService,
    private val builder: ResponseBuilder,
) {
    /** @api 获取当前管理员会话 */
    @GetMapping
    fun getSession(
        @AuthenticationPrincipal adminId: Long,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            val username: String,
            val email: String,
            @param:JsonProperty("first_name")
            val firstName: String,
            @param:JsonProperty("last_name")
            val lastName: String,
            val avatar: String?,
            val locale: String?,
            val currency: String?,
            val role: String,
        )

        adminAccessService.requireAdmin(adminId)
        val user = userService.getUserById(adminId) ?: return builder.notFound().build()
        val rs = Response(
            id = requireNotNull(user.id),
            username = user.username,
            email = user.email,
            firstName = user.firstName,
            lastName = user.lastName,
            avatar = user.avatar,
            locale = user.locale,
            currency = user.currency,
            role = user.role.name,
        )
        return builder.ok().data(rs).build()
    }
}

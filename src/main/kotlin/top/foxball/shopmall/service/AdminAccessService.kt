package top.foxball.shopmall.service

import org.springframework.stereotype.Service
import top.foxball.shopmall.entity.jdbc.Role
import top.foxball.shopmall.handler.ForbiddenException
import top.foxball.shopmall.repository.UserRepository

/** 为后台目录和评论审核接口提供统一的管理员权限检查。 */
@Service
class AdminAccessService(
    private val userRepository: UserRepository,
) {
    /** 要求指定用户具有管理员角色，否则拒绝访问。 */
    fun requireAdmin(userId: Long) {
        if (!isAdmin(userId)) {
            throw ForbiddenException("仅管理员可以执行此操作")
        }
    }

    /** 要求指定用户为普通客户，避免管理员身份代替客户提交评价。 */
    fun requireCustomer(userId: Long) {
        if (userRepository.findById(userId).orElse(null)?.role != Role.CUSTOMER) {
            throw ForbiddenException("仅普通客户可以执行此操作")
        }
    }

    /**
     * 锁定并校验普通客户；供需要在用户维度串行化创建受限资源的写操作使用。
     *
     * 与 [requireCustomer] 保持相同的拒绝语义，仅查询方式改为悲观写锁。
     */
    fun requireCustomerForUpdate(userId: Long) {
        if (userRepository.findByIdForUpdate(userId)?.role != Role.CUSTOMER) {
            throw ForbiddenException("仅普通客户可以执行此操作")
        }
    }

    /** 判断指定用户是否具有管理员角色。 */
    fun isAdmin(userId: Long): Boolean =
        userRepository.findById(userId).orElse(null)?.role == Role.ADMIN
}

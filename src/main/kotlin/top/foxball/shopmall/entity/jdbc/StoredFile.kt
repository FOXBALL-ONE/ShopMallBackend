package top.foxball.shopmall.entity.jdbc

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import java.time.LocalDateTime
import java.util.UUID

/** 配置的本地存储根目录中一份文件的元数据，不直接保存文件二进制内容。 */
@Entity
@Table(
    name = "file_metadata",
    indexes = [
        Index(name = "idx_file_metadata_owner_created", columnList = "owner_id, created_at"),
    ],
)
class StoredFile(
    @Id
    var id: UUID = UUID.randomUUID(),

    /** 所属用户 ID；不使用 JPA 关联，避免用户实体加载和级联影响文件生命周期。 */
    @Column(name = "owner_id", nullable = false)
    var ownerId: Long = 0,

    @Column(name = "original_filename", nullable = false, length = 255)
    var originalFilename: String = "",

    @Column(name = "stored_filename", nullable = false, unique = true, length = 255)
    var storedFilename: String = "",

    /** 相对于存储根目录的受控路径；读取时必须再次做根目录边界校验。 */
    @Column(name = "relative_path", nullable = false, unique = true, length = 512)
    var relativePath: String = "",

    @Column(name = "content_type", length = 255)
    var contentType: String? = null,

    @Column(name = "byte_size", nullable = false)
    var sizeBytes: Long = 0,

    /** 上传时计算的内容摘要，用于完整性核对和排查。 */
    @Column(name = "sha256", nullable = false, length = 64)
    var sha256: String = "",

    /** 实际内容所在的存储后端；当前实现支持 local，并为后续 S3 路由保留字段。 */
    @Column(nullable = false, length = 16)
    var storage: String = "local",

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,
)

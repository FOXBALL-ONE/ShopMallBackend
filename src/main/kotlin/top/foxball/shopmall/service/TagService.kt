package top.foxball.shopmall.service

import top.foxball.shopmall.entity.jdbc.Tag

interface TagService {
    fun listPublished(): List<Tag>

    fun getPublished(id: Long): Tag?

    fun listForAdmin(): List<Tag>

    fun getForAdmin(id: Long): Tag?

    fun create(source: Tag): Tag

    fun update(id: Long, source: Tag): Tag?

    fun delete(id: Long): Boolean
}

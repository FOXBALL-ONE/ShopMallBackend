package top.foxball.shopmall.ratelimit

import top.foxball.shopmall.handler.ParamErrorException

/** Validates the deliberately narrow syntax accepted for dynamic rate-limit exclusions. */
object RateLimitPathRules {
    const val MAX_PATHS = 20
    const val MAX_PATH_LENGTH = 160
    const val MAX_TOTAL_LENGTH = 2_000

    private val forbiddenPrefixes = listOf(
        "/api/auth",
        "/api/logistics/webhook",
        "/admin",
        "/actuator",
        "/error",
    )

    fun normalize(paths: List<String>): List<String> {
        if (paths.size > MAX_PATHS) {
            throw ParamErrorException("免限速路径最多允许 $MAX_PATHS 条")
        }

        val normalized = paths.map(::validateOne).distinct().sorted()
        if (normalized.size > MAX_PATHS) {
            throw ParamErrorException("免限速路径最多允许 $MAX_PATHS 条")
        }
        if (normalized.sumOf(String::length) > MAX_TOTAL_LENGTH) {
            throw ParamErrorException("免限速路径总长度不能超过 $MAX_TOTAL_LENGTH 个字符")
        }
        return normalized
    }

    /** Parses the canonical newline-separated representation persisted in Redis. */
    fun parseStored(raw: String): List<String> {
        if (raw.isEmpty()) return emptyList()
        val values = raw.split('\n')
        val normalized = normalize(values)
        if (normalized.joinToString("\n") != raw) {
            throw ParamErrorException("Redis 中的免限速路径不是规范化配置")
        }
        return normalized
    }

    /** Reject non-canonical request paths before considering any dynamically configured pattern. */
    fun isCanonicalRequestPath(path: String): Boolean =
        path.startsWith('/') &&
            !path.contains("//") &&
            !path.split('/').any { it == "." || it == ".." } &&
            !path.contains(';') &&
            !path.contains('%') &&
            !path.contains('\\') &&
            path.none { it.isWhitespace() || it.isISOControl() }

    private fun validateOne(value: String): String {
        if (value.isEmpty() || value.length > MAX_PATH_LENGTH) {
            throw ParamErrorException("免限速路径长度必须为 1 到 $MAX_PATH_LENGTH 个字符")
        }
        if (value != value.trim() || value.any { it.isWhitespace() || it.isISOControl() }) {
            throw ParamErrorException("免限速路径不能包含空白或控制字符")
        }
        if (!value.startsWith("/api/")) {
            throw ParamErrorException("免限速路径必须以 /api/ 开头")
        }
        if (
            value.contains('?') || value.contains('#') || value.contains(';') || value.contains('%') ||
            value.contains('\\') || value.contains("..") || value.contains("//") ||
            value.contains('{') || value.contains('}')
        ) {
            throw ParamErrorException("免限速路径包含不允许的字符或路径片段")
        }

        val subtree = value.endsWith("/**")
        val base = if (subtree) value.removeSuffix("/**") else value
        if (base.isEmpty() || base.endsWith('/')) {
            throw ParamErrorException("免限速路径不能以 / 结尾")
        }
        if (value.contains('*') && (!subtree || base.contains('*'))) {
            throw ParamErrorException("免限速路径仅允许末尾的 /** 通配符")
        }
        if (!SAFE_EXCLUSION_PATH.matches(value) || base.split('/').any { it == "." || it == ".." }) {
            throw ParamErrorException("免限速路径只能包含安全的 URL 路径段")
        }
        if (value == "/api/**") {
            throw ParamErrorException("不允许配置 /api/** 为免限速路径")
        }
        if (forbiddenPrefixes.any { isSameOrSegmentDescendant(base, it) }) {
            throw ParamErrorException("不允许配置认证、回调、管理或系统路径为免限速路径")
        }
        return value
    }

    private fun isSameOrSegmentDescendant(path: String, prefix: String): Boolean =
        path == prefix || path.startsWith("$prefix/")

    private val SAFE_EXCLUSION_PATH = Regex("^/api/(?:[A-Za-z0-9._~-]+/)*[A-Za-z0-9._~-]+(?:/\\*\\*)?$")
}

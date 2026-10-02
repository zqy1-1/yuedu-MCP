package com.mina.legadostudio.diagnostic

object LogRedactor {
    private val authHeader = Regex("(?i)authorization\\s*[:=]\\s*(?:bearer\\s+)?\\S+")
    private val cookieHeader = Regex("(?i)(?:set-)?cookie\\s*[:=]\\s*[^\\r\\n]+")
    private val tokenLike = Regex("(?i)(api[-_ ]?key|token)\\s*[:=]\\s*\\S+")

    fun redact(value: String): String = value
        .replace(authHeader, "authorization=***")
        .replace(cookieHeader, "cookie=***")
        .replace(tokenLike) { match -> "${match.groupValues[1]}=***" }
}

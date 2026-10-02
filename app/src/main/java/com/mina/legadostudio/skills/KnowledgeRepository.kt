package com.mina.legadostudio.skills

import android.content.Context
import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

class KnowledgeRepository(private val context: Context) {
    @Keep
    data class Hit(
        @SerializedName("path") val path: String,
        @SerializedName("title") val title: String,
        @SerializedName("snippet") val snippet: String,
    )

    private val files by lazy { walk("knowledge").filterNot { it.endsWith("index.json") } }

    fun listDocuments(): List<Hit> = KnowledgeSearch.list(files, ::read)

    fun search(query: String, limit: Int = 20): List<Hit> =
        KnowledgeSearch.search(files, ::read, query, limit)

    fun read(path: String): String {
        require(KnowledgeSearch.isReadable(path, files)) { "非法知识库路径" }
        return context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun walk(path: String): List<String> = context.assets.list(path).orEmpty().flatMap { name ->
        val child = "$path/$name"
        val nested = context.assets.list(child).orEmpty()
        if (nested.isEmpty()) listOf(child) else walk(child)
    }
}

/** 单站取证案例只对同站查询开放；域名按注册域+子域精确匹配，不被中间子串冒充。 */
internal object KnowledgeSearch {

    private data class GatedCase(
        val fileMarker: String,
        val domains: Set<String>,
        val names: Set<String>,
    )

    private val GATED_CASES = listOf(
        GatedCase(
            fileMarker = "阿里书屋",
            domains = setOf("ali75.com"),
            names = setOf("阿里书屋", "JS挑战与动态搜索避坑实战-阿里书屋"),
        ),
        GatedCase(
            fileMarker = "爱书网",
            domains = setOf("aqxsw66.com"),
            names = setOf("爱书网·耽美TXT", "TXT整本站伪目录实战-爱书网"),
        ),
    )

    private val HOST_TOKEN = Regex("[a-z0-9-]+(?:\\.[a-z0-9-]+)+")

    fun isGatedCaseFile(path: String): Boolean = gateFor(path) != null

    fun isSearchable(path: String, query: String): Boolean {
        val gate = gateFor(path) ?: return true
        val q = query.trim().lowercase()
        if (q.isEmpty()) return false
        if (gate.names.any { q.contains(it.lowercase()) }) return true
        return gate.domains.any { domain ->
            HOST_TOKEN.findAll(q).any { it.value == domain || it.value.endsWith(".$domain") }
        }
    }

    fun isReadable(path: String, files: List<String>): Boolean =
        path in files || path == "knowledge/index.json"

    fun list(files: List<String>, readText: (String) -> String): List<KnowledgeRepository.Hit> =
        files.filterNot(::isGatedCaseFile).map { describe(it, readText) }

    fun search(files: List<String>, readText: (String) -> String, query: String, limit: Int): List<KnowledgeRepository.Hit> {
        val words = query.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        val cap = limit.coerceIn(1, 100)
        val candidates = files.filter { isSearchable(it, query) }
        if (words.isEmpty()) return list(candidates, readText).take(cap)
        return candidates.mapNotNull { path ->
            val text = readText(path)
            val title = path.substringAfterLast('/').substringBeforeLast('.')
            val index = words.map { firstIndex(it, path, title, text, gateFor(path)) }.filter { it >= 0 }.minOrNull() ?: return@mapNotNull null
            val start = (index - 180).coerceAtLeast(0)
            val end = (index + 420).coerceAtMost(text.length)
            val snippet = text.substring(start, end).replace(Regex("\\s+"), " ").trim()
            KnowledgeRepository.Hit(path, title, snippet)
        }.take(cap)
    }

    // 词命中顺序：正文 → 标题/路径。受限案例把本站的域名/子域/站名词视为命中，
    // 使 www.ali75.com 这类子域查询也能命中只写过 m.ali75.com 的正文。
    private fun firstIndex(word: String, path: String, title: String, text: String, gate: GatedCase?): Int {
        var index = text.indexOf(word, ignoreCase = true)
        if (index < 0) index = path.indexOf(word, ignoreCase = true)
        if (index < 0) index = title.indexOf(word, ignoreCase = true)
        if (index < 0 && gate != null && wordMatchesGate(word, gate)) index = 0
        return index
    }

    private fun wordMatchesGate(word: String, gate: GatedCase): Boolean {
        val w = word.trim().lowercase()
        if (w.isEmpty()) return false
        if (gate.names.any { w.contains(it.lowercase()) }) return true
        return gate.domains.any { domain ->
            HOST_TOKEN.findAll(w).any { it.value == domain || it.value.endsWith(".$domain") }
        }
    }

    private fun gateFor(path: String): GatedCase? {
        val name = path.substringAfterLast('/').substringBeforeLast('.')
        return GATED_CASES.firstOrNull { name.contains(it.fileMarker, ignoreCase = true) }
    }

    private fun describe(path: String, readText: (String) -> String) = KnowledgeRepository.Hit(
        path,
        path.substringAfterLast('/').substringBeforeLast('.'),
        readText(path).lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(240),
    )
}

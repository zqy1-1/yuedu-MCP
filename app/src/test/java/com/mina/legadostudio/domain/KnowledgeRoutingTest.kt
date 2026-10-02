package com.mina.legadostudio.domain

import com.mina.legadostudio.skills.KnowledgeRepository
import com.mina.legadostudio.skills.KnowledgeSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KnowledgeRoutingTest {

    private val ali = "knowledge/JS挑战与动态搜索避坑实战-阿里书屋.md"
    private val aishu = "knowledge/TXT整本站伪目录实战-爱书网.md"
    private val rrssk = "knowledge/第三方搜索逆向实战-rrssk.md"

    private fun knowledgeDir(): File = listOf(
        File("src/main/assets/knowledge"),
        File("app/src/main/assets/knowledge"),
    ).first { it.isDirectory }

    private val files: List<String> by lazy {
        knowledgeDir().listFiles().orEmpty()
            .filter { it.isFile && it.name != "index.json" }
            .map { "knowledge/${it.name}" }
            .sorted()
    }

    private fun readText(path: String): String =
        File(knowledgeDir(), path.substringAfter("knowledge/")).readText()

    private fun search(query: String, limit: Int = 20) =
        KnowledgeSearch.search(files, ::readText, query, limit)

    private fun paths(hits: List<KnowledgeRepository.Hit>) = hits.map { it.path }.toSet()

    // ---------- 同站放行 ----------

    @Test
    fun sameSiteQueriesReturnTheCase() {
        assertTrue(ali in paths(search("阿里书屋")))
        assertTrue(ali in paths(search("m.ali75.com")))
        assertTrue(ali in paths(search("www.ali75.com")))
        assertTrue(ali in paths(search("M.ALI75.COM")))
        assertTrue(ali in paths(search("JS挑战与动态搜索避坑实战-阿里书屋")))

        assertTrue(aishu in paths(search("爱书网·耽美TXT")))
        assertTrue(aishu in paths(search("dm.aqxsw66.com")))
        assertTrue(aishu in paths(search("aqxsw66.com")))
        assertTrue(aishu in paths(search("TXT整本站伪目录实战-爱书网")))
    }

    @Test
    fun subdomainsAndPathsMatchRegisteredDomain() {
        // 注册域的子域/带路径的 host 都算同站
        assertTrue(ali in paths(search("https://m.ali75.com/sscc/")))
        assertTrue(aishu in paths(search("https://dm.aqxsw66.com/read.php")))
        // 同站任意子域词命中同一案例，即使正文只写过 m.ali75.com
        assertTrue(ali in paths(search("www.ali75.com")))
        assertTrue(ali in paths(search("sub.ali75.com")))
        assertTrue(aishu in paths(search("dm.aqxsw66.com")))
    }

    @Test
    fun subdomainHitDoesNotMixUnrelatedDocs() {
        // 子域词只放行同案例，不把其他无字面命中的文档混进来
        val hits = search("www.ali75.com")
        assertTrue(ali in paths(hits))
        hits.filter { it.path != ali }.forEach { hit ->
            val text = readText(hit.path) + hit.title + hit.path
            assertTrue("${hit.path} 应有字面命中", text.contains("www.ali75.com", ignoreCase = true))
        }
    }

    // ---------- 泛词 / 空查询 / 跨站不命中 ----------

    @Test
    fun genericQueriesHideBothCases() {
        listOf("JS 挑战", "动态搜索", "搜索无结果", "var c2", "内容正在载入", "伪目录", "TXT 整本", "read.php")
            .forEach { q ->
                assertFalse(ali in paths(search(q)))
                assertFalse(aishu in paths(search(q)))
            }
    }

    @Test
    fun emptyQueryHidesBothCases() {
        assertFalse(ali in paths(search("")))
        assertFalse(aishu in paths(search("")))
        assertFalse(ali in paths(search("   ")))
        // 空查询仍返回其他文档
        assertTrue(search("").isNotEmpty())
    }

    @Test
    fun listDocumentsHideBothCases() {
        val all = KnowledgeSearch.list(files, ::readText)
        assertFalse(all.any { it.path == ali })
        assertFalse(all.any { it.path == aishu })
        assertTrue(all.any { it.path == rrssk })
    }

    // ---------- 中间子串 / 相似域名不冒充 ----------

    @Test
    fun lookalikeDomainsDoNotOpenCases() {
        assertFalse(ali in paths(search("notali75.example")))
        assertFalse(ali in paths(search("ali75.evil.com")))
        assertFalse(ali in paths(search("myali75.com")))
        assertFalse(aishu in paths(search("aqxsw66.evil.com")))
        assertFalse(aishu in paths(search("notaqxsw66.com")))
        // 短片段/通用镜像域不冒充本站
        assertFalse(ali in paths(search("ali75")))
        assertFalse(aishu in paths(search("aishu995")))
        assertFalse(aishu in paths(search("downshu123")))
    }

    @Test
    fun crossSiteQueriesDoNotHitCases() {
        listOf("番茄小说 fanqie 字体混淆", "qyue.com 整本 TXT 目录", "example.org JS 挑战")
            .forEach { q ->
                assertFalse(ali in paths(search(q)))
                assertFalse(aishu in paths(search(q)))
            }
    }

    // ---------- 通用文档与 rrssk 不受影响 ----------

    @Test
    fun rrsskAndOtherDocsAlwaysSearchable() {
        assertTrue(rrssk in paths(search("rrssk")))
        assertTrue(rrssk in paths(search("")))
        // 其他文档按关键字正常命中
        assertTrue(search("验证码").isNotEmpty())
    }

    // ---------- limit / 直读合同 ----------

    @Test
    fun limitRespectedAfterFiltering() {
        assertTrue(search("", 5).size <= 5)
        assertTrue(search("", 3).size <= 3)
        // 受限案例被过滤后不占名额
        assertFalse(paths(search("", 3)).contains(ali))
    }

    @Test
    fun directReadContract() {
        // 已知 path 直读不受限（含受限案例）
        assertTrue(KnowledgeSearch.isReadable(ali, files))
        assertTrue(KnowledgeSearch.isReadable(aishu, files))
        assertTrue(KnowledgeSearch.isReadable("knowledge/index.json", files))
        assertFalse(KnowledgeSearch.isReadable("knowledge/不存在.md", files))
        assertFalse(KnowledgeSearch.isReadable("knowledge/../etc/passwd", files))
    }
}

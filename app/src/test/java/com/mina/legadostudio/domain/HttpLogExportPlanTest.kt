package com.mina.legadostudio.domain

import com.mina.legadostudio.data.db.HttpLogEntity
import com.mina.legadostudio.data.db.HttpLogSummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter

class HttpLogExportPlanTest {

    /** List-backed fake：与 DAO 同语义（DESC、id<beforeId、cap:% 排除在入库时模拟）。 */
    private class FakeSource(rows: List<HttpLogEntity>) : HttpLogExportPlan.Source {
        // cap:% 在真实 SQL 里被排除——fake 直接不收录它们，等价于 SQL 过滤后的表
        private val table = rows.filter { it.contextId?.startsWith("cap:") != true }.sortedByDescending { it.id }
        private fun HttpLogEntity.toSummary() = HttpLogSummary(
            id = id, method = method, url = url, finalUrl = finalUrl, statusCode = statusCode,
            durationMs = durationMs, error = error, createdAt = createdAt,
            sourceAnchor = sourceAnchor, contextId = contextId, originKind = originKind,
            requestHeaders = requestHeaders, redirectChain = redirectChain,
        )

        override suspend fun summaryDayPage(dayStart: Long, dayEnd: Long, beforeId: Long, limit: Int): List<HttpLogSummary> =
            table.filter { it.createdAt in dayStart..dayEnd && it.id < beforeId }.take(limit).map { it.toSummary() }

        override suspend fun summaryAllPage(beforeId: Long, limit: Int): List<HttpLogSummary> =
            table.filter { it.id < beforeId }.take(limit).map { it.toSummary() }

        override suspend fun entitiesByIds(ids: List<Long>): List<HttpLogEntity> =
            table.filter { it.id in ids.toSet() }
    }

    private fun log(
        id: Long,
        url: String,
        createdAt: Long = id * 1000,
        sourceAnchor: String? = null,
        contextId: String? = null,
        originKind: String? = "mcp_fetch",
        requestHeaders: String = "{}",
        redirectChain: String = "[]",
        responseBody: String = "body$id",
    ) = HttpLogEntity(
        id = id, method = "GET", url = url, finalUrl = url, statusCode = 200, durationMs = 10,
        requestHeaders = requestHeaders, responseHeaders = "{}", requestBody = "", responseBody = responseBody,
        error = "", redirectChain = redirectChain, sourceAnchor = sourceAnchor,
        contextId = contextId, originKind = originKind, createdAt = createdAt,
    )

    @Test
    fun dayScopeExportsOnlyThatDay() = runBlocking {
        val dayA = java.time.LocalDate.parse("2026-09-30")
        val zone = java.time.ZoneId.systemDefault()
        val aStart = dayA.atStartOfDay(zone).toInstant().toEpochMilli()
        val aEnd = dayA.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val src = FakeSource(listOf(
            log(1, "https://a.example.com/1", createdAt = aStart + 100),
            log(2, "https://a.example.com/2", createdAt = aEnd - 100),
            log(3, "https://a.example.com/3", createdAt = aEnd + 10), // 次日
        ))
        val scan = HttpLogExportPlan.scan(
            src, HttpLogExportPlan.Request(HttpLogExportPlan.Scope.DAY, "2026-09-30", null),
            dayWindow = aStart to aEnd,
        )
        assertEquals(listOf(1L, 2L), scan.ids)
        assertEquals(2, scan.total)
    }

    @Test
    fun allScopeCrossesDaysForAnchor() = runBlocking {
        val zone = java.time.ZoneId.systemDefault()
        val d1 = java.time.LocalDate.parse("2026-09-28").atStartOfDay(zone).toInstant().toEpochMilli()
        val d2 = java.time.LocalDate.parse("2026-09-29").atStartOfDay(zone).toInstant().toEpochMilli()
        val d3 = java.time.LocalDate.parse("2026-09-30").atStartOfDay(zone).toInstant().toEpochMilli()
        val src = FakeSource(listOf(
            // 三天前就有该锚点的显式日志——「全部日期 + 锚点」必须跨天命中
            log(1, "https://aqxsw66.com/a", createdAt = d1, sourceAnchor = "aqxsw66.com"),
            log(2, "https://other.com/x", createdAt = d2),
            log(3, "https://aqxsw66.com/b", createdAt = d3, sourceAnchor = "aqxsw66.com"),
        ))
        val scan = HttpLogExportPlan.scan(
            src, HttpLogExportPlan.Request(HttpLogExportPlan.Scope.ALL, "2026-09-30", "aqxsw66.com"),
            dayWindow = null,
        )
        assertEquals(listOf(1L, 3L), scan.ids)
        assertEquals(mapOf("aqxsw66.com" to 2), scan.bucketCounts)
    }

    @Test
    fun keysetPaginationIsNotTruncatedByPageSize() = runBlocking {
        // 超过一页（200）的全库扫描必须翻完，不能停在页边界
        val rows = (1L..450L).map { log(it, "https://s.example.com/$it", createdAt = it * 1000) }
        val scan = HttpLogExportPlan.scan(
            FakeSource(rows), HttpLogExportPlan.Request(HttpLogExportPlan.Scope.ALL, "2026-09-30", null),
            dayWindow = null,
        )
        assertEquals(450, scan.total)
        assertEquals(450, scan.ids.size)
        assertEquals(1L, scan.ids.first())
        assertEquals(450L, scan.ids.last())
    }

    @Test
    fun unattributedRowsWithUrlEvidenceJoinAnchorBucket() = runBlocking {
        // 老日志 sourceAnchor=null 但 URL 指向候选锚点域——全库扫描时应凭 SAME_HOST 证据归并
        val src = FakeSource(listOf(
            log(1, "https://aqxsw66.com/book/1", createdAt = 1000, sourceAnchor = null),
            log(2, "https://aqxsw66.com/book/2", createdAt = 2000, sourceAnchor = "aqxsw66.com"),
            log(3, "https://unrelated.com/z", createdAt = 3000),
        ))
        val scan = HttpLogExportPlan.scan(
            src, HttpLogExportPlan.Request(HttpLogExportPlan.Scope.ALL, "2026-09-30", "aqxsw66.com"),
            dayWindow = null,
        )
        assertEquals(listOf(1L, 2L), scan.ids)
    }

    @Test
    fun refererEvidenceJoinsButUrlDomainDoesNotSelfAttribute() = runBlocking {
        // 镜像域：URL 在镜像站、Referer 指回源站 → 归源站；URL 自身的镜像域不产生候选
        val mirror = log(
            1, "https://downshu.example.org/img/1", createdAt = 1000,
            requestHeaders = "{\"Referer\":\"https://aqxsw66.com/book/1\"}",
        )
        val src = FakeSource(listOf(mirror, log(2, "https://aqxsw66.com/book/1", createdAt = 500)))
        val scan = HttpLogExportPlan.scan(
            src, HttpLogExportPlan.Request(HttpLogExportPlan.Scope.ALL, "2026-09-30", "aqxsw66.com"),
            dayWindow = null,
        )
        // 镜像行凭 Referer 归 aqxsw66.com；若 URL 域也入候选，镜像会自立门户成第二桶
        assertEquals(listOf(1L, 2L), scan.ids)
        assertEquals(mapOf("aqxsw66.com" to 2), scan.bucketCounts)
    }

    @Test
    fun selectedScopeExportsExactlySelectedIds() = runBlocking {
        val src = FakeSource(listOf(
            log(1, "https://a.com/1", createdAt = 1000),
            log(2, "https://a.com/2", createdAt = 2000),
            log(3, "https://a.com/3", createdAt = 3000),
        ))
        // 勾选 1 和 3：不跑归属过滤，直取 id
        val scan = HttpLogExportPlan.scan(
            src, HttpLogExportPlan.Request(HttpLogExportPlan.Scope.SELECTED, "2026-09-30", null, selectedIds = listOf(3L, 1L)),
            dayWindow = null,
        )
        assertEquals(listOf(1L, 3L), scan.ids)
        assertEquals(2, scan.total)
    }

    @Test
    fun captureContextRowsAreExcludedFromDayAndAll() = runBlocking {
        val src = FakeSource(listOf(
            log(1, "https://a.com/1", createdAt = 1000, contextId = "cap:abc"),
            log(2, "https://a.com/2", createdAt = 2000),
        ))
        val scanDay = HttpLogExportPlan.scan(
            src, HttpLogExportPlan.Request(HttpLogExportPlan.Scope.ALL, "x", null), dayWindow = null,
        )
        assertEquals(listOf(2L), scanDay.ids)
    }

    @Test
    fun describeReflectsRealScope() {
        val day = HttpLogExportPlan.describe(HttpLogExportPlan.Request(HttpLogExportPlan.Scope.DAY, "2026-09-30", null))
        assertTrue(day.contains("2026-09-30"))
        val all = HttpLogExportPlan.describe(HttpLogExportPlan.Request(HttpLogExportPlan.Scope.ALL, "2026-09-30", "aqxsw66.com"))
        assertTrue(all.contains("全部日期") && all.contains("aqxsw66.com"))
        val sel = HttpLogExportPlan.describe(HttpLogExportPlan.Request(HttpLogExportPlan.Scope.SELECTED, "2026-09-30", null, listOf(1L, 2L)))
        assertTrue(sel.contains("已勾选 2 条"))
    }

    @Test
    fun scopeOptionsHideSelectedWhenNothingChecked() {
        val none = HttpLogExportPlan.scopeOptions("2026-09-30", 10, 0, null)
        assertEquals(2, none.size)
        assertFalse(none.any { it.key == HttpLogExportPlan.SCOPE_SELECTED })
        val some = HttpLogExportPlan.scopeOptions("2026-09-30", 10, 3, "aqxsw66.com")
        assertEquals(3, some.size)
        assertTrue(some.last().label.contains("3 条"))
        assertTrue(some[0].hint.contains("aqxsw66.com"))
    }

    @Test
    fun defaultScopeKeyPrefersSelectedWhenChecked() {
        // 有勾选时默认「仅已勾选」：按钮已明示「导出（已勾选 N）」，默认当日会让一键分享把当日全库发出去；
        // 无勾选（选项无 selected 项，含 null/空列表 = 单范围弹窗）默认当日
        val withChecked = HttpLogExportPlan.scopeOptions("2026-09-30", 4774, 3, null)
        assertEquals(HttpLogExportPlan.SCOPE_SELECTED, HttpLogExportPlan.defaultScopeKey(withChecked))
        val noChecked = HttpLogExportPlan.scopeOptions("2026-09-30", 4774, 0, null)
        assertEquals(HttpLogExportPlan.SCOPE_DAY, HttpLogExportPlan.defaultScopeKey(noChecked))
        assertEquals(HttpLogExportPlan.SCOPE_DAY, HttpLogExportPlan.defaultScopeKey(null))
        assertEquals(HttpLogExportPlan.SCOPE_DAY, HttpLogExportPlan.defaultScopeKey(emptyList()))
    }

    @Test
    fun forEachEntityBatchDeliversSortedAndChunked() = runBlocking {
        val rows = (1L..2000L).map { log(it, "https://a.com/$it", createdAt = it) }
        val src = FakeSource(rows)
        val delivered = mutableListOf<Long>()
        HttpLogExportPlan.forEachEntityBatch(src, (1L..2000L).toList()) { batch -> delivered += batch.map { it.id } }
        assertEquals(2000, delivered.size)
        assertEquals((1L..2000L).toList(), delivered)
    }

    @Test
    fun writerProducesSameJsonShapeAsInMemoryExporter() = runBlocking {
        val logs = listOf(
            log(1, "https://a.com/1", createdAt = 1000, sourceAnchor = "a.com"),
            log(2, "https://b.com/2", createdAt = 2000),
        )
        val expected = LogMultiFormatExporter.http(logs, "2026-09-30", LogExportFormat.JSON, redact = false)
        val sw = StringWriter()
        val w = HttpLogExportWriter(sw, "HTTP 事务 · 全部日期", LogExportFormat.JSON, redact = false,
            groupByAnchor = false, anchors = listOf("a.com"), contextAnchors = emptyMap())
        w.begin(2)
        w.writeBatch(logs)
        w.end()
        val streamed = sw.toString()
        // 结构一致：合法 JSON 数组、同键集、行数一致（行序按 createdAt 升序，与内存版相同）
        val el = com.google.gson.JsonParser.parseString(expected).asJsonArray
        val sl = com.google.gson.JsonParser.parseString(streamed).asJsonArray
        assertEquals(el.size(), sl.size())
        assertEquals(el[0].asJsonObject.keySet(), sl[0].asJsonObject.keySet())
        assertEquals(el.map { it.asJsonObject.get("id").asLong }, sl.map { it.asJsonObject.get("id").asLong })
    }

    @Test
    fun idsByBucketCarriesAscendingIdsPerBucket() = runBlocking {
        // 桶分组是第二趟扫描的副产物：桶内 id 升序（扫描页是 DESC 翻页，结果必须反转回升序），
        // 且每桶 id 集合与 bucketCounts 对齐——TXT 分节按桶序交批直接用本表，不再 O(N×K) 反查
        val src = FakeSource(listOf(
            log(1, "https://a.test/1", createdAt = 100, sourceAnchor = "a.test"),
            log(2, "https://b.test/1", createdAt = 200, sourceAnchor = "b.test"),
            log(3, "https://a.test/2", createdAt = 300, sourceAnchor = "a.test"),
            log(4, "https://unrelated.example/x", createdAt = 400),
        ))
        val scan = HttpLogExportPlan.scan(
            src, HttpLogExportPlan.Request(HttpLogExportPlan.Scope.ALL, "2026-09-30", null),
            dayWindow = null,
        )
        assertEquals(listOf(1L, 3L), scan.idsByBucket["a.test"])
        assertEquals(listOf(2L), scan.idsByBucket["b.test"])
        assertEquals(listOf(4L), scan.idsByBucket[HttpLogAttributor.UNATTRIBUTED_KEY])
        assertEquals(scan.bucketCounts.keys.toList(), scan.idsByBucket.keys.toList())
        assertEquals(scan.ids, scan.idsByBucket.values.flatten().sorted())
    }

    @Test
    fun scanThrowsWhenHitsExceedMaxRows() = runBlocking {
        // 命中超过 MAX_ROWS 抛 ScanLimitExceeded，不静默截断（防巨大导出卡死/OOM）
        // 只喂投影页：到上限就抛，不走到取实体那步
        val src = object : HttpLogExportPlan.Source {
            private val table = (1L..(HttpLogExportPlan.MAX_ROWS + 1L)).map {
                HttpLogSummary(
                    id = it, method = "GET", url = "https://s.example.com/$it", finalUrl = "",
                    statusCode = 200, durationMs = 0, error = "", createdAt = it,
                    sourceAnchor = "s.example.com", contextId = null, originKind = null,
                    requestHeaders = "{}", redirectChain = "[]",
                )
            }.sortedByDescending { it.id }
            override suspend fun summaryDayPage(dayStart: Long, dayEnd: Long, beforeId: Long, limit: Int) =
                emptyList<HttpLogSummary>()
            override suspend fun summaryAllPage(beforeId: Long, limit: Int) =
                table.filter { it.id < beforeId }.take(limit)
            override suspend fun entitiesByIds(ids: List<Long>) = emptyList<HttpLogEntity>()
        }
        val ex = runCatching {
            HttpLogExportPlan.scan(
                src, HttpLogExportPlan.Request(HttpLogExportPlan.Scope.ALL, "2026-09-30", null),
                dayWindow = null,
            )
        }.exceptionOrNull()
        assertTrue(ex is HttpLogExportPlan.ScanLimitExceeded)
    }
}

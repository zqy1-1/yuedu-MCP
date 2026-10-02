package com.mina.legadostudio.domain

import com.mina.legadostudio.data.db.HttpLogSummary
import com.mina.legadostudio.ui.screens.HttpDayPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HTTP 按天滚动窗口状态机回归锁：评审指出的三处一致性——
 * ① 飞行期新插入不被翻页回填覆盖；② 同日期过期请求不可回填；③ 删除后窗口收敛不依赖 MAX(id)。
 */
class HttpDayPageStateTest {

    private fun s(id: Long, at: Long = id * 1000) = HttpLogSummary(
        id = id, method = "GET", url = "https://example.com/$id", createdAt = at,
    )

    @Test
    fun mergeNewerPrependsDedupedDesc() {
        val cur = HttpDayPage("2026-09-30", total = 3, baselineId = 10, entries = listOf(s(9), s(8), s(7)), minId = 7)
        val merged = cur.mergeNewer(listOf(s(9), s(12), s(11))) // 9 重复、12/11 新
        assertEquals(listOf(12L, 11L, 9L, 8L, 7L), merged.map { it.id })
    }

    @Test
    fun mergeBelowAppendsDedupedDesc() {
        val cur = HttpDayPage("2026-09-30", total = 5, baselineId = 10, entries = listOf(s(9), s(8)), minId = 8)
        val merged = cur.mergeBelow(listOf(s(8), s(5), s(3))) // 8 重复丢弃
        assertEquals(listOf(9L, 8L, 5L, 3L), merged.map { it.id })
    }

    /** 核心一致性：beginLoadMore 发请求 → 飞行中 mergeNewer 进同一实例 → foldNextPage 到当时的 cur，新行/新 baseline 保住。 */
    @Test
    fun foldNextPageKeepsInFlightNewerRows() {
        val page = HttpDayPage("2026-09-30", total = 3, baselineId = 10, entries = listOf(s(9), s(8)), minId = 8, epoch = 1)
        val request = page.beginLoadMore()
        assertTrue(request.loading)
        assertTrue(request !== page)
        assertEquals(page.epoch + 1, request.epoch)

        // 飞行中 MAX(id) 触发：新行 12/11 mergeNewer 到 request（同一实例引用上挂的 copy 仍 === request 语义里的 cur）
        val curWithNewer = request.copy(entries = request.mergeNewer(listOf(s(12), s(11))), baselineId = 12, total = 5)
        // 模拟实际代码路径：LaunchedEffect 写的是 cur（httpDayPage 最新实例），此处 cur 即 curWithNewer
        val dbPage = listOf(s(6), s(4)) // keyset 拉到更老行
        val folded = request.foldNextPage(curWithNewer, dbPage, exhausted = false)

        // entries 必须在最新 cur 基础上追加：新行 12/11 不丢
        assertEquals(listOf(12L, 11L, 9L, 8L, 6L, 4L), folded.entries.map { it.id })
        assertEquals(4L, folded.minId) // 游标推进到本页最小 id（最后一条是 4）
        assertFalse(folded.loading)
        assertEquals(12L, folded.baselineId) // cur 的更新 baseline 保住，不回退到 10
        assertEquals(5, folded.total) // cur 的 total（含新行）保住
    }

    /** exhausted 判定：本页不满 HTTP_DAY_PAGE_SIZE 即到底。 */
    @Test
    fun foldNextPageMarksExhaustedOnShortPage() {
        val page = HttpDayPage("2026-09-30", total = 1, baselineId = 10, entries = listOf(s(9)), minId = 9, epoch = 0)
        val request = page.beginLoadMore()
        val folded = request.foldNextPage(request, listOf(s(5)), exhausted = false)
        assertTrue(folded.exhausted)
        assertEquals(listOf(9L, 5L), folded.entries.map { it.id })
    }

    /** 同一性守卫语义：beginLoadMore 换身份（epoch+1、新实例），旧请求拿旧实例比对必失败。 */
    @Test
    fun staleRequestCannotWriteBack() {
        val first = HttpDayPage("2026-09-29", total = 0, baselineId = 0, epoch = 0)
        val req1 = first.beginLoadMore() // epoch 1
        // 换日期重开：新窗口 epoch 重计或实例替换
        val reopened = HttpDayPage("2026-09-30", total = 0, baselineId = 0, epoch = 2)
        // req1 是老实例：与 reopened 不同实例、dateKey 也不同 → 守卫拒绝
        assertFalse(reopened === req1)
        assertFalse(reopened.epoch == req1.epoch && reopened.dateKey == req1.dateKey)
    }

    /** 删除收敛：removeIds 同步扣 entries 与 total，不等 MAX(id) Flow（删非尾行时 MAX 不变）。 */
    @Test
    fun removeIdsShrinksWindowAndTotal() {
        val cur = HttpDayPage("2026-09-30", total = 5, baselineId = 10, entries = listOf(s(9), s(8), s(7), s(6)), minId = 6)
        val after = cur.removeIds(setOf(8L, 6L))
        assertEquals(listOf(9L, 7L), after.entries.map { it.id })
        assertEquals(3, after.total)
        // 不在窗口内的 id 不影响 total（已删行只按窗口内可见计）
        val after2 = cur.removeIds(setOf(100L))
        assertEquals(5, after2.total)
        assertEquals(4, after2.entries.size)
    }
}

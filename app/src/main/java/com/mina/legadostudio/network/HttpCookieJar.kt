package com.mina.legadostudio.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.mina.legadostudio.verification.DomainKey

/**
 * 进程内会话 Cookie jar：服务端 Set-Cookie 经 OkHttp 解析成 [Cookie] 后原样入库，
 * 回带判定完全交给 OkHttp 原生 [Cookie.matches]（hostOnly/domain 后缀匹配、path、secure），
 * 过期由本类按 expiresAt 惰性清理。不自写解析，不持久化、不写日志：cookie 是会话凭据。
 *
 * 身份键 = (name, domain, path) —— RFC 6265 §5.3 的 cookie 身份三元组：同一三元组的
 * host-only 与 Domain= cookie 相互替换（后写覆盖），与浏览器行为一致；
 * hostOnly 差异保存在 Cookie 对象内，由 matches() 决定能否回带到子域。
 *
 * 服务端 cookie 语义（全部委托 OkHttp）：
 * - 无 Domain 属性 → host-only：只回带精确同 host，不流向兄弟子域；
 * - Domain=example.com → 该域及全部子域可回带；
 * - Path 属性 → 仅匹配路径前缀回带；
 * - Secure → 仅 https 回带；非 https 来源下发的 Secure cookie 直接拒收（RFC 6265bis
 *   的 Strict Secure 语义），绝不降级成非 secure；
 * - 过期/删除：expiresAt<=now 立即移除（OkHttp 已把 Max-Age/Expires 解析成时间戳）。
 *
 * 显式来源（RuntimeCookieStore prefs / WebView 采集 / cookie.setCookie JS API）：
 * 不把显式串塞进 RFC cookie 库——它的"按可注册域共享整串"是 RuntimeCookieStore 既有语义
 * （DomainKey eTLD+1 桶，跨子域共享，与修改前一致，刻意保留），不能悄悄扩成 CookieJar 通用规则。
 * 实现为 loadForRequest 的瞬时 overlay：provider 自己判定该 URL 是否命中其域桶，命中的
 * 同名值覆盖服务端 cookie（官方 cookie.setCookie 写入立即生效的直觉）；overlay 不入库。
 *
 * 重定向：OkHttp 对每一跳都走 loadForRequest/saveFromResponse，跨域跳转按目标 host 的原生
 * matches() 判定，host-only cookie 不会泄露到别的 host。
 */
class StudioCookieJar(
    private val explicitHeaderProvider: (String) -> String? = { null },
    private val now: () -> Long = { System.currentTimeMillis() },
) : CookieJar {

    private val lock = Any()

    /** 仅存服务端 Set-Cookie 来源；键 = name|domain|path（RFC 身份三元组）。 */
    private val store = LinkedHashMap<String, Cookie>()

    // 分隔符取 ASCII 0x01，避免含分隔符的字段拼出同一键
    private fun key(c: Cookie): String = "${c.name}\u0001${c.domain}\u0001${c.path}"

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val merged = LinkedHashMap<String, Cookie>()
        synchronized(lock) {
            val nowMs = now()
            val it = store.entries.iterator()
            while (it.hasNext()) {
                val c = it.next().value
                // matches() 不查过期，本类负责惰性清理（session cookie expiresAt=MAX_VALUE 永不命中）
                if (nowMs >= c.expiresAt) { it.remove(); continue }
                if (c.matches(url)) merged[c.name] = c
            }
        }
        // 显式 overlay：域桶判定在 provider 内完成，不在 jar 里扩权；同名覆盖服务端值。
        explicitHeaderProvider(url.toString()).orEmpty()
            .split(';')
            .map { it.trim() }
            .filter { it.contains('=') }
            .forEach { pair ->
                val name = pair.substringBefore('=').trim()
                if (name.isEmpty()) return@forEach
                val value = pair.substringAfter('=').trim()
                merged[name] = runCatching {
                    Cookie.Builder().name(name).value(value).domain(url.host).build()
                }.getOrNull() ?: return@forEach
            }
        return merged.values.toList()
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val nowMs = now()
        synchronized(lock) {
            cookies.forEach { c ->
                // 非安全来源下发的 Secure cookie 拒收（Strict Secure），不降级
                if (c.secure && !url.isHttps) return@forEach
                val k = key(c)
                if (c.expiresAt <= nowMs) store.remove(k) else store[k] = c
            }
        }
    }

    /** RuntimeCookieStore.clear 的 jar 侧同步：清掉该可注册域（DomainKey 桶）内全部服务端 cookie。 */
    fun clearDomain(url: String) {
        val httpUrl = url.toHttpUrlOrNull() ?: return
        val domainKey = DomainKey.fromHost(httpUrl.host)
        synchronized(lock) {
            store.values.toList()
                .filter { DomainKey.fromHost(it.domain) == domainKey }
                .forEach { store.remove(key(it)) }
        }
    }
}

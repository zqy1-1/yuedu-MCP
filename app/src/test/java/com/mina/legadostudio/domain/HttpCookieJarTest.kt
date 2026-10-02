package com.mina.legadostudio.domain

import com.mina.legadostudio.network.HttpFetcher
import com.mina.legadostudio.network.StudioCookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * aaawz 实战缺口回归 + 审查修订：服务端 cookie 走 OkHttp Cookie 原生 matches() 语义
 * （hostOnly/Domain/Path/Secure/expiresAt 全部委托 OkHttp 解析与判定），不再自写解析。
 * 显式 RuntimeCookieStore 串保持 prefs 域桶语义，作为 overlay 不进 RFC 库。
 *
 * 注：MockWebServer 只能监听 localhost/127.0.0.1（两个不同 host），子域语义（a.x vs b.x）
 * 用 Cookie.parse + jar.loadForRequest 直测；服务器收发链路由同 host 用例实测。
 */
class HttpCookieJarTest {

    private fun jar() = StudioCookieJar()

    // ---------- 基础收发（MockWebServer 实测链路） ----------

    @Test fun setCookieOnResponseIsSentOnNextGet() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("list").setHeader("Set-Cookie", "reader_guard_id=g1; Path=/"))
            server.enqueue(MockResponse().setBody("chapter"))
            server.start()
            val fetcher = HttpFetcher(cookieJar = jar())
            fetcher.fetch(HttpFetcher.FetchRequest(server.url("/list").toString()))
            fetcher.fetch(HttpFetcher.FetchRequest(server.url("/chapter/1").toString()))
            server.takeRequest()
            assertEquals("reader_guard_id=g1", server.takeRequest().getHeader("Cookie"))
        }
    }

    @Test fun hostOnlyCookieNotSentToOtherHost() {
        // 无 Domain 属性 = host-only：localhost 桶里的 cookie 不发给 127.0.0.1（不同 host）
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("a").setHeader("Set-Cookie", "guard=x; Path=/"))
            server.enqueue(MockResponse().setBody("b"))
            server.start()
            val fetcher = HttpFetcher(cookieJar = jar())
            fetcher.fetch(HttpFetcher.FetchRequest(server.url("/one").toString()))
            fetcher.fetch(HttpFetcher.FetchRequest("http://127.0.0.1:${server.port}/two"))
            server.takeRequest()
            assertNull(server.takeRequest().getHeader("Cookie"))
        }
    }

    @Test fun setCookieSurvivesRedirectHop() {
        // 302 中间跳下发的 Set-Cookie：最终跳请求已带同一 cookie
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/final").setHeader("Set-Cookie", "hop=h1; Path=/"))
            server.enqueue(MockResponse().setBody("done"))
            server.start()
            val fetcher = HttpFetcher(cookieJar = jar())
            val result = fetcher.fetch(HttpFetcher.FetchRequest(server.url("/start").toString()))
            assertEquals("done", result.body)
            server.takeRequest()
            assertEquals("hop=h1", server.takeRequest().getHeader("Cookie"))
        }
    }

    @Test fun crossHostRedirectDoesNotLeakCookie() {
        // localhost 的 host-only cookie 重定向到 127.0.0.1 时不回带（跨 host 不泄露）
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("set").setHeader("Set-Cookie", "guard=x; Path=/"))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://127.0.0.1:${server.port}/other"))
            server.enqueue(MockResponse().setBody("landed"))
            server.start()
            val fetcher = HttpFetcher(cookieJar = jar())
            fetcher.fetch(HttpFetcher.FetchRequest(server.url("/set").toString()))
            fetcher.fetch(HttpFetcher.FetchRequest(server.url("/go").toString()))
            server.takeRequest()                       // /set
            server.takeRequest()                       // /go（带 guard=x，同 host）
            assertNull(server.takeRequest().getHeader("Cookie")) // 127.0.0.1 跳
        }
    }

    @Test fun pathRestrictedCookieOnlyMatchesPrefix() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("set").setHeader("Set-Cookie", "scoped=s; Path=/api"))
            server.enqueue(MockResponse().setBody("in"))
            server.enqueue(MockResponse().setBody("out"))
            server.start()
            val fetcher = HttpFetcher(cookieJar = jar())
            fetcher.fetch(HttpFetcher.FetchRequest(server.url("/api/set").toString()))
            fetcher.fetch(HttpFetcher.FetchRequest(server.url("/api/inside").toString()))
            fetcher.fetch(HttpFetcher.FetchRequest(server.url("/other").toString()))
            server.takeRequest()
            assertEquals("scoped=s", server.takeRequest().getHeader("Cookie"))
            assertNull(server.takeRequest().getHeader("Cookie"))
        }
    }

    @Test fun secureCookieFromHttpIsRejected() {
        // http 下发的 Secure cookie：Strict Secure 拒收，绝不降级成非 secure 再回带
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("set").setHeader("Set-Cookie", "sec=1; Secure; Path=/"))
            server.enqueue(MockResponse().setBody("next"))
            server.start()
            val fetcher = HttpFetcher(cookieJar = jar())
            fetcher.fetch(HttpFetcher.FetchRequest(server.url("/set").toString()))
            fetcher.fetch(HttpFetcher.FetchRequest(server.url("/next").toString()))
            server.takeRequest()
            assertNull(server.takeRequest().getHeader("Cookie"))
        }
    }

    @Test fun expiresDeletesCookie() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("set").setHeader("Set-Cookie", "guard=x; Path=/"))
            server.enqueue(MockResponse().setBody("del").setHeader("Set-Cookie", "guard=; Max-Age=0; Path=/"))
            server.enqueue(MockResponse().setBody("after"))
            server.start()
            val fetcher = HttpFetcher(cookieJar = jar())
            fetcher.fetch(HttpFetcher.FetchRequest(server.url("/set").toString()))
            fetcher.fetch(HttpFetcher.FetchRequest(server.url("/del").toString()))
            fetcher.fetch(HttpFetcher.FetchRequest(server.url("/after").toString()))
            server.takeRequest()
            assertEquals("guard=x", server.takeRequest().getHeader("Cookie"))
            assertNull(server.takeRequest().getHeader("Cookie"))
        }
    }

    @Test fun expiredCookieIsNotSent() {
        // Cookie.parse 用真实系统时钟写死 expiresAt；假时钟向后拨 11s 触发 load 的惰性过期
        val url = "https://www.example.com/a".toHttpUrl()
        val realNow = System.currentTimeMillis()
        val cookie = okhttp3.Cookie.parse(url, "k=v; Max-Age=10")!!
        val jarExpired = StudioCookieJar(now = { realNow + 11_000 })
        jarExpired.saveFromResponse(url, listOf(cookie))
        assertEquals(0, jarExpired.loadForRequest(url).size)
        // 对照：假时钟=realNow，同一 cookie 仍能回带
        val jarAlive = StudioCookieJar(now = { realNow })
        jarAlive.saveFromResponse(url, listOf(okhttp3.Cookie.parse(url, "k=v; Max-Age=10")!!))
        assertEquals(1, jarAlive.loadForRequest(url).size)
    }

    // ---------- 子域 / Domain 语义 ----------
    // Cookie.parse 的 Domain 处理需要 PSL 资源（okhttp/publicsuffix），单测环境不可用时
    // 用 Cookie.Builder 构造等价 Cookie 对象（hostOnly vs domain 差异来自 hostOnly()/domain() 构造器）。

    @Test fun hostOnlyNotSentToSiblingSubdomain() {
        val j = jar()
        // hostOnly cookie：只回带精确同 host，不发兄弟子域（与无 Domain 的 Set-Cookie 等价）
        val c = okhttp3.Cookie.Builder().name("h").value("1").hostOnlyDomain("a.example.com").build()
        j.saveFromResponse("http://a.example.com/x".toHttpUrl(), listOf(c))
        assertEquals(1, j.loadForRequest("http://a.example.com/y".toHttpUrl()).size)
        assertEquals(0, j.loadForRequest("http://b.example.com/y".toHttpUrl()).size)
    }

    @Test fun domainAttributeCookieSentToSubdomain() {
        val j = jar()
        // Domain=example.com 的等价对象：回带全部子域，不发无关域
        val c = okhttp3.Cookie.Builder().name("w").value("1").domain("example.com").build()
        j.saveFromResponse("http://a.example.com/x".toHttpUrl(), listOf(c))
        assertEquals(1, j.loadForRequest("http://b.example.com/y".toHttpUrl()).size)
        assertEquals(0, j.loadForRequest("http://other.com/".toHttpUrl()).size)
    }

    @Test fun secureCookieNotSentOverHttp() {
        val j = jar()
        val c = okhttp3.Cookie.Builder().name("sec").value("1").secure().hostOnlyDomain("www.example.com").build()
        j.saveFromResponse("https://www.example.com/".toHttpUrl(), listOf(c))
        assertEquals(1, j.loadForRequest("https://www.example.com/".toHttpUrl()).size)
        assertEquals(0, j.loadForRequest("http://www.example.com/".toHttpUrl()).size)
    }

    // ---------- 禁用 / 显式 overlay ----------

    @Test fun fetchRequestCanDisableCookieJar() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("set").setHeader("Set-Cookie", "guard=x; Path=/"))
            server.enqueue(MockResponse().setBody("off"))
            server.enqueue(MockResponse().setBody("on"))
            server.start()
            val fetcher = HttpFetcher(cookieJar = jar())
            fetcher.fetch(HttpFetcher.FetchRequest(server.url("/set").toString()))
            // 禁用的一跳：不回带、也不吸收 Set-Cookie
            fetcher.fetch(HttpFetcher.FetchRequest(server.url("/off").toString(), useCookieJar = false))
            fetcher.fetch(HttpFetcher.FetchRequest(server.url("/on").toString()))
            server.takeRequest()
            assertNull(server.takeRequest().getHeader("Cookie"))
            assertEquals("guard=x", server.takeRequest().getHeader("Cookie"))
        }
    }

    @Test fun explicitProviderCarriedViaOverlay() {
        // RuntimeCookieStore 显式串（prefs/WebView 采集）以 overlay 合流，两行 Cookie 头不并存
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("page"))
            server.start()
            val j = StudioCookieJar(explicitHeaderProvider = { "manual=m1" })
            val fetcher = HttpFetcher(cookieHeaderProvider = { "manual=m1" }, cookieJar = j)
            fetcher.fetch(HttpFetcher.FetchRequest(server.url("/p").toString()))
            assertEquals("manual=m1", server.takeRequest().getHeader("Cookie"))
        }
    }

    @Test fun explicitValueWinsOverServerSetCookie() {
        // 站点先下发 guard=s1，之后显式串 guard=manual：下一次请求以显式值为准
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("set").setHeader("Set-Cookie", "guard=s1; Path=/"))
            server.enqueue(MockResponse().setBody("after"))
            server.start()
            var explicit = ""
            val j = StudioCookieJar(explicitHeaderProvider = { explicit })
            val fetcher = HttpFetcher(cookieHeaderProvider = { explicit }, cookieJar = j)
            fetcher.fetch(HttpFetcher.FetchRequest(server.url("/set").toString()))
            explicit = "guard=manual"
            fetcher.fetch(HttpFetcher.FetchRequest(server.url("/after").toString()))
            server.takeRequest()
            assertEquals("guard=manual", server.takeRequest().getHeader("Cookie"))
        }
    }

    @Test fun noJarKeepsLegacyExplicitHeaderBehaviour() {
        // 不传 cookieJar（旧路径）：只发显式头，Set-Cookie 不落库
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("set").setHeader("Set-Cookie", "guard=x"))
            server.enqueue(MockResponse().setBody("after"))
            server.start()
            val fetcher = HttpFetcher(cookieHeaderProvider = { "manual=m1" })
            fetcher.fetch(HttpFetcher.FetchRequest(server.url("/set").toString()))
            fetcher.fetch(HttpFetcher.FetchRequest(server.url("/after").toString()))
            assertEquals("manual=m1", server.takeRequest().getHeader("Cookie"))
            assertEquals("manual=m1", server.takeRequest().getHeader("Cookie"))
        }
    }
}

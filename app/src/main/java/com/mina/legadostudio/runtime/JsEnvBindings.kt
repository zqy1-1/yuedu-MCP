package com.mina.legadostudio.runtime

import androidx.annotation.Keep
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.mina.legadostudio.verification.RuntimeCookieStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 官方阅读 JS 环境同名对象：cookie / cache / source。
 * 注入 eval_js 与书源 <js>/{{}} 求值上下文，方法名与官方保持一致，AI 按官方 js-api 文档写的 JS 不需要改写。
 * 说明：cache 为进程内实现（官方是磁盘持久层）；调试工具进程内有效即可，重启后请重新写入。
 */

/**
 * 登录信息桩存储：真机 source.getLoginInfo/putLoginInfo 与登录 UI/书源 loginUi 配置联动，
 * 沙箱没有登录界面，退化为「按书源锚点进程内共享的 Map」。写入可见（putLoginInfo 后
 * getLoginInfo 能读回），同一书源跨次 eval/debug 共享，模拟真机里登录信息已保存的状态；
 * 首次读取返回 null 并由沙箱在日志行提示「桩实现，真机行为可能不同」。
 * 锚点用书源 bookSourceUrl，取不到时共用 "" 键。
 */
object RuntimeLoginStore {
    private val data = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()
    /** 每个锚点已提示过的桩 API 名，避免每次调用都重复打同一行 warning */
    private val noted = CopyOnWriteArraySet<String>()

    fun anchorOf(source: Any?): String = when (source) {
        is SourceJsApi -> source.anchor
        else -> ""
    }

    fun map(anchor: String): ConcurrentHashMap<String, String> =
        data.getOrPut(anchor) { ConcurrentHashMap() }

    fun noteOnce(anchor: String, api: String, warn: (String) -> Unit) {
        if (noted.add("$anchor|$api")) {
            warn("$api 为沙箱桩实现：沙箱无登录界面，返回进程内模拟值（默认空）；真机行为可能不同（真机值来自书源 loginUi 表单/用户输入）")
        }
    }
}

@Keep
class CookieJsApi(private val cookies: RuntimeCookieStore?) {
    /** 官方 cookie.getCookie(url)：该域完整 Cookie 串 */
    fun getCookie(url: String): String = cookies?.headerFor(url).orEmpty()

    /** 官方 cookie.getKey(url, key)：取单个 Cookie 值 */
    fun getKey(url: String, key: String): String =
        getCookie(url).split(';').map { it.trim() }
            .firstOrNull { it.substringBefore('=') == key }?.substringAfter('=', "").orEmpty()

    /** 官方 cookie.setCookie(url, cookie)：按名合并，保留该域其他 Cookie（如登录态） */
    fun setCookie(url: String, cookie: String) {
        cookies?.merge(url, cookie) ?: error("CookieStore 不可用")
    }

    /** 官方 cookie.replaceCookie(url, cookie)：整串替换该域 Cookie */
    fun replaceCookie(url: String, cookie: String) {
        cookies?.set(url, cookie) ?: error("CookieStore 不可用")
    }

    /** 官方 cookie.removeCookie(url)：清除该域 Cookie */
    fun removeCookie(url: String) {
        cookies?.clear(url) ?: error("CookieStore 不可用")
    }

    /** 官方 cookie.setWebCookie(url, cookie)：本实现 Runtime 与 WebView 同源，等价 setCookie */
    fun setWebCookie(url: String, cookie: String) = setCookie(url, cookie)
}

/** 进程内缓存，TTL 语义对齐官方 CacheManager（saveTime 秒，0=不过期）。 */
object RuntimeCacheStore {
    private data class Item(val value: String, val expireAt: Long)
    private val disk = linkedMapOf<String, Item>()
    private val memory = linkedMapOf<String, Any?>()

    @Synchronized
    fun put(key: String, value: String, saveTimeSec: Long) {
        disk[key] = Item(value, if (saveTimeSec <= 0) Long.MAX_VALUE else System.currentTimeMillis() + saveTimeSec * 1000)
    }

    @Synchronized
    fun get(key: String): String? {
        val item = disk[key] ?: return null
        if (System.currentTimeMillis() >= item.expireAt) { disk.remove(key); return null }
        return item.value
    }

    @Synchronized
    fun delete(key: String) { disk.remove(key) }

    @Synchronized
    fun putMemory(key: String, value: Any?) { memory[key] = value }

    @Synchronized
    fun getFromMemory(key: String): Any? = memory[key]

    @Synchronized
    fun deleteMemory(key: String) { memory.remove(key) }
}

@Keep
class CacheJsApi(private val store: RuntimeCacheStore = RuntimeCacheStore) {
    /** 官方 cache.put(key, value[, saveTime])；saveTime 秒，0/缺省 = 不过期 */
    @JvmOverloads
    fun put(key: String, value: String, saveTime: Any? = null) {
        store.put(key, value, when (saveTime) {
            is Number -> saveTime.toLong()
            is String -> saveTime.toLongOrNull() ?: 0L
            else -> 0L
        })
    }

    /** 官方 cache.get(key[, onlyDisk])；onlyDisk 形参保留（本实现无分层） */
    @JvmOverloads
    fun get(key: String, onlyDisk: Any? = null): String? = store.get(key)

    /** 官方 cache.delete(key) */
    fun delete(key: String) = store.delete(key)

    /** 官方 cache.putMemory(key, value)：存任意对象（仅内存） */
    fun putMemory(key: String, value: Any?) = store.putMemory(key, value)

    /** 官方 cache.getFromMemory(key) */
    fun getFromMemory(key: String): Any? = store.getFromMemory(key)

    /** 官方 cache.deleteMemory(key) */
    fun deleteMemory(key: String) = store.deleteMemory(key)
}

/** 官方 source 对象：调试期书源变量仓库。底层 state 与 debug 流程共享，JS 写入对后续规则可见。 */
@Keep
class SourceJsApi(
    private val state: MutableMap<String, String> = mutableMapOf(),
    /** 登录信息锚点（书源 bookSourceUrl）；桩存储按键隔离，见 [RuntimeLoginStore] */
    internal val anchor: String = "",
    private val warn: (String) -> Unit = {},
) {
    private val gson = Gson()

    /** 官方 source.getVariable()：全部变量的 JSON 串 */
    fun getVariable(): String = gson.toJson(state)

    /** 官方 source.setVariable(json)：整体替换变量 */
    fun setVariable(variable: String?) {
        state.clear()
        if (variable.isNullOrBlank()) return
        runCatching {
            JsonParser.parseString(variable).asJsonObject.entrySet().forEach { state[it.key] = it.value.asString }
        }
    }

    /** 官方 source.putVariable(json)：同 setVariable */
    fun putVariable(variable: String?) = setVariable(variable)

    /** 官方 source.put(key, value) */
    fun put(key: String, value: Any?) { state[key] = value?.toString().orEmpty() }

    /** 官方 source.get(key) */
    fun get(key: String): String? = state[key]

    /** 官方 source.getLoginInfo()：登录信息 JSON 串；沙箱桩，未写入过返回 null 并提示一次 */
    fun getLoginInfo(): String? {
        RuntimeLoginStore.noteOnce(anchor, "source.getLoginInfo", warn)
        return RuntimeLoginStore.map(anchor).takeIf { it.isNotEmpty() }?.let { gson.toJson(it) }
    }

    /** 官方 source.getLoginInfoMap()：登录信息 Map；沙箱桩 */
    fun getLoginInfoMap(): Map<String, String>? {
        RuntimeLoginStore.noteOnce(anchor, "source.getLoginInfoMap", warn)
        return RuntimeLoginStore.map(anchor).takeIf { it.isNotEmpty() }?.toMap()
    }

    /** 官方 source.getLoginInfo(key)：单个登录信息值；沙箱桩 */
    fun getLoginInfo(key: String): String? {
        RuntimeLoginStore.noteOnce(anchor, "source.getLoginInfo", warn)
        return RuntimeLoginStore.map(anchor)[key]
    }

    /** 官方 source.putLoginInfo(json)：登录函数存放登录信息（JSON 串写入，键值均转字符串） */
    fun putLoginInfo(info: String?) {
        RuntimeLoginStore.noteOnce(anchor, "source.putLoginInfo", warn)
        if (info.isNullOrBlank()) return
        runCatching {
            JsonParser.parseString(info).asJsonObject.entrySet()
                .forEach { RuntimeLoginStore.map(anchor)[it.key] = it.value.asString }
        }
    }

    /** 官方 source.putLoginInfo(map)：Map 形态重载 */
    fun putLoginInfo(info: Map<*, *>?) {
        RuntimeLoginStore.noteOnce(anchor, "source.putLoginInfo", warn)
        info?.forEach { (k, v) -> if (k != null) RuntimeLoginStore.map(anchor)[k.toString()] = v?.toString().orEmpty() }
    }

    /** 官方 source.putLoginInfo(key, value)：单键写入重载 */
    fun putLoginInfo(key: String, value: Any?) {
        RuntimeLoginStore.noteOnce(anchor, "source.putLoginInfo", warn)
        RuntimeLoginStore.map(anchor)[key] = value?.toString().orEmpty()
    }
}

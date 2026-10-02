# JS挑战与动态搜索避坑实战 —— 以阿里书屋 (m.ali75.com) 为例

> **仅同站取证，非通用模板**：本文全部结论只对 m.ali75.com 有效。其他站点出现相似现象（`var c2=`、`内容正在载入`、搜索 0 结果）时，请到目标站重新取证；本文仅证明"该站曾有此拦截形态"，其中参数、路径、算法、选择器均不承诺可迁移，禁止把下文骨架当任何网站的默认解法。
>
> **证据分级**：本文基于 2026-09 对 m.ali75.com 的 HTTP 日志与探索期 POST 探针。已证实的是「挑战页特征与拦截行为」；第 3 节的解题代码是**方案示意，未经成品链路实测**，不要原样抄进成品源。探索期探针 ≠ 成品调用链。

## 1. 核心现象与致命踩坑

在 m.ali75.com 的取证中，最容易犯的错误是：
**误以为把解密脚本写在 `loginCheckJs` 里就万事大吉了**。

### 踩坑分析：
- **`loginCheckJs` 不是调试期的过盾通道**：它写进成品书源、由官方阅读 App 在每次请求响应后执行；**Studio 沙箱的 `debug_source`/`check_source`/`eval_js` 不执行它**（运行时无此钩子）。把解密逻辑只写进 `loginCheckJs`，调试期根本看不到它生效。
- **`loginCheckJs` 也不适合承载整段挑战解密+搜索重放**：它的契约是「检测响应 → 改写成放行后的响应返回」，不是发起多段解密请求再返回搜索结果的地方；挑战解密应放在 `searchUrl @js`（返回 URL）或 `ruleSearch.bookList @js`（返回元素数组）。
- **后果**：向 `/sscc/` 发送 POST 搜索时，服务器直接返回 551 字节的 `<div ...>内容正在载入……<script>var c2=...</script>` 拦截页（HTTP 日志中确为 200 返回）。由于未过验证，`ruleSearch`（如 `dl.B, dl.S`）在拦截页上根本匹配不到书籍，表现为**搜索永远无结果**。
- **别误判成「规则选不中」**：这是请求被挑战拦截，不是 CSS 选择器失效；改 `bookList` 选择器没有用。
- **Gson 转义冲突**：在 JSON 字符串内书写复杂正则表达式时，如果反斜杠未正确双重转义（如 `\\\"([^\\\"]+)\\\"`），Gson 解析时会直接抛出 `Invalid escape sequence` 异常导致书源无法加载或调试报错。巨型 `@js` 建议用 `save_source` 的 `fields` 参数分字段传入。

---

## 2. 解法方向：动态链路段用 JS，字段提取仍用 CSS

对 m.ali75.com 的 `/sscc/` 搜索接口拦截，当时取证思路是把挑战解密前置到 **JS 控制的请求链**里（以下步骤为本站历史记录，未经成品链路实测）：

1. 检查本地 CookieJar 是否已有有效通行 Cookie；
2. 若无 Cookie 或首试命中 `<div ...>内容正在载入……`，在 JS 内截取 `c2` 与挑战脚本路径；
3. 抓取挑战 JS，提取核心算法（如 `function ajax(xxyyu)...`）动态 `eval`；
4. 请求验证接口换取新 Cookie，调用 `cookie.setCookie(baseUrl, totalCookie)` 存入；
5. 带上新 Cookie 发起 POST 搜索拿到结果 HTML，交给 `ruleSearch` 解析。

**位置选择（重要纠错）**：`searchUrl` 的 `@js:` **必须返回可请求的 URL（或 `url,{选项}`），不能返回 HTML 正文**——运行时会把返回值当请求地址再抓一次。所以「JS 里完成搜索请求并返回结果 HTML」这个方案**不能放在 `searchUrl`**。两个可行落点：

- `searchUrl` 只负责过挑战、种 Cookie，然后**返回搜索接口 URL**（POST 参数挂 URL 选项 `,{method,body,headers}`），让 `ruleSearch` 正常解析响应；
- 或把「发请求 + 从 HTML 提取书籍数组」整段写进 `ruleSearch.bookList` 的 `@js:`（`result`/`src` 是搜索响应体，可在其中 `java.ajax`/`java.connect` 追加请求，返回元素数组，`name`/`author`/`bookUrl` 字段照常解析）。

不要把 HTML 正文塞进 `searchUrl` 返回值——那是已翻车的写法。

---

## 3. 取证留档：过挑战思路示意（证据，不是可复用模板）

本节代码是**当时的探索取证记录**，不是可复用的代码模板：其中硬编码的 `c2`/`nnxswnn`/`ajax(xxyyu)`/`/sscc/` 均为本站特有且随时失效。即使目标站同为 m.ali75.com 也须重新实测；其他站点一律不得照抄。

下面脚本演示过挑战思路。**注意其中 `res.header(name)`、`res.bytes()`、`java.connect` 的多参数扩展重载均为 Studio 沙箱实现（`StudioJsApi`/`StudioJsResponse`），官方阅读 App 未验证可移植**；写跨环境成品时改用 `js-api.md` 官方清单内的 `java.ajax`/`java.get`/`java.post`/`headers()`/`cookie.getKey` 等。

```javascript
// 示意：过 c2 挑战换取通行 Cookie（沙箱专有 API 已标注，成品需改写）
(function(){
  var kw = java.encodeURI(key, 'gbk');
  var postBody = 'act=<实际act参数>&q=' + kw;
  var c = cookie.getCookie(baseUrl) || '';
  var hd = { 'Referer': baseUrl, 'Content-Type': 'application/x-www-form-urlencoded', 'Cookie': c };
  var res = java.connect(baseUrl + '/sscc/', hd, 'POST', postBody, 'gbk'); // connect(url,hdr,method,body,charset) 沙箱重载
  var html = res.body();
  if (/内容正在载入|nnxswnn|c2=/i.test(html)) {
    var c2Match = html.match(/var\s+c2\s*=\s*"([^"]+)"/);
    var jsMatch = html.match(/src="(\/nnxswnn\/[^"]+)"/);
    if (c2Match && jsMatch) {
      try {
        var c1 = (res.header('set-cookie') || '') + ';' + c;   // header(name) 为沙箱专有
        var jsCode = java.connect(baseUrl + jsMatch[1], { 'Referer': baseUrl, 'Cookie': c1 }).body() || '';
        var fnMatch = jsCode.match(/function ajax\(xxyyu\)[\s\S]*?return temp\.toLowerCase\(\)\}/);
        if (fnMatch) {
          eval(fnMatch[0]);
          var ajaxRes = java.connect(baseUrl + ajax(c2Match[1]), { 'Referer': baseUrl, 'Cookie': c1 });
          cookie.setCookie(baseUrl, c1 + '; ' + (ajaxRes.header('set-cookie') || ''));
          // 重新请求搜索拿 HTML —— 但此处返回 html 不能用作 searchUrl 返回值！
          // 放在 bookList @js 时：return 解析出的元素数组；放在 searchUrl 时：return 搜索 URL + 选项
        }
      } catch(e) { java.log(e); }
    }
  }
  /* searchUrl 场景必须 return URL 字符串；bookList @js 场景 return 元素数组 */
})()
```

---

## 4. 关键规则自检清单

1. **单项 Header 获取**：沙箱中可用 `res.header('set-cookie')` 大小写不敏感取单头；跨环境成品优先 `res.headers()` 里按官方方式取（官方 `StrResponse.headers()` 返回头串，需正则抓 `Set-Cookie`）。
2. **二进制获取**：页面是 GBK 且编码失真时，沙箱可 `res.bytes()` 取原始字节后用 `new Packages.java.lang.String(bytes, "gbk")` 重构（**不是** `new java.lang.String`，后者不存在）。
3. **Cookie 持久化**：解密完成后务必 `cookie.setCookie(baseUrl, totalCookie)`，后续目录与正文抓取直接享用通行凭证。
4. **回到官方机制**：过盾后字段提取仍然优先 CSS；`ruleSearch` 的 `bookList`/`name`/`bookUrl` 照常写选择器，JS 只负责挑战与请求链。

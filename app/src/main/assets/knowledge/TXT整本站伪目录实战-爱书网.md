# TXT 整本站伪目录实战 —— 以爱书网·耽美TXT (dm.aqxsw66.com) 为例

> 案例：纯 TXT 下载站，没有"章节"概念，一本书=一个 TXT 文件。要包成可阅读的源，需把整本当单章，并从在线阅读页 `read.php` 重建"伪正文"。
> **仅同站取证，非通用模板**：本文全部结论只对 dm.aqxsw66.com 有效。其他站点即使同为"一本书=一个文件"结构，也请到目标站重新取证；本文仅证明"该站曾有此形态"，其中 hash 参数名、read.php 路径、镜像域名、选择器、文件名格式均不承诺可迁移，禁止把下文骨架当任何整本站的默认解法。
> **证据分级**：站点结构与单页正文行为经 2026-09-29 HTTP 日志实证（事务 #2933–#3029）。**`read.php?page=N` 逐页取到的是单页正文，不等于已验证「整本分页全文」——多页合并链路、sort 参数均未实测**。本文 JSON 为 `jsonc` 示意骨架（含注释），不是可直接导入的成品，与附件成品书源严格区分。

## 0. 换站边界（先读这条）

本文的伪目录骨架是 **dm.aqxsw66.com 的取证留档**，不是跨站模板。其他整本站必须先自行取证站点结构（第 5 节差异项只是"本站是这样"的记录，不代表别站同构），确认结构相近后才可参考思路；结构不同则重新设计，禁止套用。

核心思路一句话：**目录只造一条"全文"章节，章节 URL 指向站点自带的在线阅读页 `read.php`，正文规则在该页取内容。**

---

## 1. 站点结构（实证部分）

- 主站：`dm.aqxsw66.com`（Cloudflare + PHP7.2）。
- 搜索：`GET /search.php?page={page}&kw={key}`，列表是 `.file-list .file-card` 卡片，链接 `a.file-card-link@href` 指向详情页 `file.php?hash=<32位hex>`。
- 详情页 `file.php?hash=<h>`：`.elsetext p` 是简介，4 个 `a[href*=down.php]` 是下载镜像（dm.downshu123 / dm.downshu321 / w.aishu995 / w.aishu9996），`#myLink3` 是在线阅读入口。**页面本身在 `.content-box > span` 内嵌了第 1 页正文。**
- 在线阅读：`read.php?name=<文件名>&file=file/<hash>&page=N`，由详情页 `page_go()` 跳；正文在 `.content-box`，翻页是纯 JS 改 `?page=`，**页面里没有 `.page-link` 元素**。
- 下载：`down.php/file/<hash>` 挂多个镜像域。

## 2. 致命坑（实测验证，照着避开）

1. **`file=` 不能带 `.txt` 后缀**：`file=file/<hash>` 正常；`file=file/<hash>.txt` 返回 200 但 body 是「错误：文件不存在或违规已删除」且只有 1 页。从 hash 拼 read.php 时必须用 `[a-f0-9]+` 之类把后缀剥掉，别用 `file=([^&"]+)` 一把抓。
2. **read.php 翻页是纯 JS 改 `?page=`，DOM 里没有 `.page-link`**：日志只证实 `page=1` 单页正文可读；**`page=N` 逐页抓全本这条链路未实测**（页数上限、末页判定、各页内容完整性均无证据）。要写分页请在 `nextContentUrl`/`read.php?page=` 上**先实跑验证**，不能从「单页成功」推断「分页整本已成」。
3. **下载镜像 ≠ 阅读自动换源**：详情页 `a[href*=down.php]` 收集到的是 **TXT 文件下载地址**（二进制响应），阅读端不会拿它们当阅读源自动切换；`downloadUrls` 字段只是把这些地址存起来备用，真正的阅读正文仍走 read.php。域名不同注册域的镜像不要全局硬编码归属本站。
4. **`name=` 参数非必需但要带**：不带 `name` 也能读出正文（实测），但站内 `page_go()` 跳转都带它，带上对齐站点行为、降低被风控差异命中的概率。

## 3. 取证留档：当时的 jsonc 骨架（证据，不是可复用模板；上线前必须实跑）

```jsonc
{
  "bookSourceUrl": "https://dm.aqxsw66.com",
  "searchUrl": "/search.php?page={{page}}&kw={{key}}",
  "ruleSearch": {
    "bookList": ".file-list .file-card",
    "bookUrl": "a.file-card-link@href",
    // 列表项是纯文件名《书名》(作者).txt，用 JS 双正则拆书名/作者
    "name": "@js:/* 见下 */",
    "author": "@js:/* 见下 */"
  },
  "ruleBookInfo": {
    "intro": ".elsetext p@text",
    "downloadUrls": "a[href*=down.php]@href",   // 收集下载镜像地址备用；不等于阅读换源
    "tocUrl": "@js:/* 从 hash 拼 read.php，见下 */"
  },
  "ruleToc": {
    // 单条=整本。chapterList 选正文容器仅是「单页内容可行」的证据；
    // 若 read.php 确认支持 ?page=N 全本分页，应改用 nextContentUrl 让正文规则翻页
    "chapterList": ".content-box",
    "chapterName": "@js:'全文'",
    "chapterUrl": "href##^$##{{baseUrl}}"        // 回填 tocUrl，让正文继续走 read.php
  },
  "ruleContent": {
    "content": ".content-box span@html",
    // 若分页已实测：nextContentUrl 写下一页规则；未实测前只覆盖第 1 页
    "replaceRegex": "##提醒/广告行正则"           // 正文清洗走这里，别塞进 JS
  }
}
```

`ruleBookInfo.tocUrl` 的 `@js:`（带 `name=` 对齐站点跳转）：

```js
@js:
(function(){
  var h = (baseUrl.match(/hash=([a-f0-9]+)/i) || [])[1];
  if(!h) return '';
  var nm = (result.match(/<title>(.*?)<\/title>/)||[])[1] || '';
  return 'https://dm.aqxsw66.com/read.php?name='+java.encodeURI(nm)+'&file=file/'+h;
})()
```

`ruleSearch.name/author`（文件名《书名》(作者) 双正则）：

```js
@js:
(function(){
  var t = result || '';
  var m = t.match(/《(.*?)》/); return m? m[1] : t.replace(/\.txt.*/,'');
})()
// author 同理取 《...》之后 / 文件名末尾 (作者) 段
```

## 4. 自检清单

1. read.php 的 `file=` 已剥 `.txt` 后缀（否则 200 但报"文件不存在"）。
2. `chapterList` 选正文容器，单条"全文"；`chapterUrl` 用 `##^$##{{baseUrl}}` 回填 tocUrl。
3. **分页与换源分开核对**：`downloadUrls` 只是下载地址收集，不是阅读换源机制；`read.php?page=N` 的整本分页链路**未实测**，默认只覆盖第 1 页——要在正文里拿全本，需先实跑 `nextContentUrl` 或 read.php 分页再声明支持。
4. explore 若配 `sort=hits/week/month` 类参数，**上线前必须实跑一次**（本案例 sort 参数从未被真实请求验证过，是未验证缺口）。
5. CF 站点重试多；命中"文件不存在"时应核对 hash/路径，而不是假定阅读端会自动换镜像。

## 5. 换站取证清单（本站的值是什么；换站必须全部重取证，勿沿用）

- hash 参数名（本站 `hash=`，兄弟站可能是别的）、read.php 的实际路径与可用镜像域、
- 列表卡片选择器与文件名格式（`《书名》(作者).txt` vs 其它）、
- 详情页简介选择器（`.elsetext`）、在线阅读入口 id（`#myLink3`）、
- 正文容器（`.content-box`）与提醒行文案（决定 replaceRegex）、
- **`read.php?page=N` 是否真正分页返回后续正文**（逐页实测，勿推断）。

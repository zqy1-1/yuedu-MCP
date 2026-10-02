package com.mina.legadostudio.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.mina.legadostudio.StudioApplication
import com.mina.legadostudio.data.db.ProjectEntity
import com.mina.legadostudio.domain.SourceCatalog
import com.mina.legadostudio.domain.SourceGroup
import com.mina.legadostudio.export.ReaderCatalog
import com.mina.legadostudio.export.SourceImportPayload
import com.mina.legadostudio.ui.theme.GlassCard
import com.mina.legadostudio.ui.theme.GlassTopBar
import com.mina.legadostudio.ui.theme.LocalStudioFullscreen
import com.mina.legadostudio.ui.theme.LocalStudioHaze
import com.mina.legadostudio.ui.theme.StudioSpacing
import com.mina.legadostudio.ui.theme.studioBottomInset
import com.mina.legadostudio.ui.theme.studioTopInset
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun SourcesScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as StudioApplication
    val haze = LocalStudioHaze.current
    val fullscreen = LocalStudioFullscreen.current
    val projects by app.projects.observe().collectAsState(initial = emptyList())
    val groups = remember(projects) { SourceCatalog.groupByDomain(projects) }
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf("") }
    var chooser by remember { mutableStateOf<Pair<String, List<ReaderCatalog.App>>?>(null) }
    var pendingDelete by remember { mutableStateOf<ProjectEntity?>(null) }
    var pendingDeleteGroup by remember { mutableStateOf<SourceGroup?>(null) }
    var pendingClearAll by remember { mutableStateOf(false) }
    var viewingProject by remember { mutableStateOf<ProjectEntity?>(null) }
    var expanded by remember { mutableStateOf(setOf<String>()) }

    DisposableEffect(viewingProject != null) {
        fullscreen?.value = viewingProject != null
        onDispose { fullscreen?.value = false }
    }

    fun show(message: String) {
        notice = message
        toastNotice(context, message)
    }

    /** 分享书源 JSON：统一包成 JSON 数组（阅读导入只认数组），写入 cacheDir/exports 后经 FileProvider 授权调起系统分享面板（QQ/微信等可直接收发） */
    fun shareSource(project: ProjectEntity) {
        scope.launch {
            runCatching {
                // IO 内只做文件写盘与 intent 组装；startActivity 回主线程——
                // 部分 ROM（Android 8+）从后台线程调起分享面板会唤不出/丢授权
                val intent = withContext(Dispatchers.IO) {
                    val payload = SourceImportPayload.arrayJson(project.sourceJson)
                    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                    val file = File(dir, "${exportFileName(project)}.json")
                    file.writeText(payload, Charsets.UTF_8)
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                    Intent(Intent.ACTION_SEND).apply {
                        // MIME 用 text/plain 而非 application/json：主流社交 App（微信/QQ）对
                        // application/json 的 ACTION_SEND 支持差，分享面板可能直接无目标；
                        // text/plain 能落到「发送给好友/保存」入口，文件名仍带 .json 后缀
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, file.name)
                        putExtra(Intent.EXTRA_TITLE, project.name.ifBlank { project.id })
                        // ClipData 携带 content:// URI：Android 8+ 上仅靠 EXTRA_STREAM+GRANT flag
                        // 对 Direct Share/预览缩略图的目标进程不授权，ClipData 才覆盖全部目标
                        clipData = ClipData.newUri(context.contentResolver, file.name, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
                val chooser = Intent.createChooser(intent, "分享书源 JSON").apply {
                    if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                withContext(Dispatchers.Main) { context.startActivity(chooser) }
            }.onSuccess { show("已生成「${project.name.ifBlank { project.id }}」JSON，选择应用分享") }
                .onFailure { show("分享失败：${it.message ?: it.javaClass.simpleName}") }
        }
    }

    fun copySource(project: ProjectEntity) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText("bookSource", SourceImportPayload.arrayJson(project.sourceJson))
        clipboard?.setPrimaryClip(clip)
        notice = "已复制「${project.name.ifBlank { project.id }}」书源"
    }

    fun importSource(sourceJson: String) {
        launchReaderImport(context, sourceJson, onNeedChooser = { json, apps -> chooser = json to apps }, onNotice = ::show)
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().then(if (haze != null) Modifier.hazeSource(haze) else Modifier),
            contentPadding = PaddingValues(start = StudioSpacing.screen, end = StudioSpacing.screen, top = StudioSpacing.screenTopBase + studioTopInset(), bottom = StudioSpacing.screenBottomBase + studioBottomInset()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                GlassCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("本地书源库", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (projects.isEmpty()) "暂无记录。外部 MCP 客户端 调用 save_source 后将按站点域名分组列出。"
                            else "按站点域名分组，组内按保存时间倒序。同站点自动保留最新 5 个版本；支持一键删除整组与一键清空。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (notice.isNotBlank()) {
                            Text(notice, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            items(groups, key = { it.domain }) { group ->
                DomainSourceGroup(
                    group = group,
                    expanded = group.domain in expanded,
                    onToggle = {
                        expanded = if (group.domain in expanded) expanded - group.domain else expanded + group.domain
                    },
                    onDetail = { viewingProject = it },
                    onCopy = ::copySource,
                    onImport = { importSource(it.sourceJson) },
                    onShare = ::shareSource,
                    onDelete = { pendingDelete = it },
                    onDeleteGroup = { pendingDeleteGroup = it },
                )
            }
        }
        GlassTopBar(
            "书源",
            actions = {
                if (projects.isNotEmpty()) {
                    TextButton(onClick = { pendingClearAll = true }) {
                        Text("清空", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }

    chooser?.let { (json, apps) ->
        ReaderChooserDialog(
            json = json,
            apps = apps,
            onDismiss = { chooser = null },
            onPick = { sourceJson, packageName ->
                chooser = null
                startReaderImport(context, sourceJson, packageName, ::show)
            },
        )
    }

    pendingDelete?.let { project ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除书源") },
            text = { Text("将从工坊本地库移除「${project.name.ifBlank { project.id }}」（${formatTime(project.updatedAt)}），不影响同域名其他版本及已导入阅读客户端的副本。此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    val target = project
                    pendingDelete = null
                    scope.launch {
                        runCatching { app.projects.delete(listOf(target.id)) }
                            .onSuccess { show("已删除 1 条书源") }
                            .onFailure { show(it.message.orEmpty()) }
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }

    pendingDeleteGroup?.let { group ->
        AlertDialog(
            onDismissRequest = { pendingDeleteGroup = null },
            title = { Text("一键删除站点书源") },
            text = { Text("将彻底删除域名「${group.domain}」下的全部 ${group.items.size} 个版本记录。此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    val ids = group.items.map { it.id }
                    val domain = group.domain
                    pendingDeleteGroup = null
                    scope.launch {
                        runCatching { app.projects.delete(ids) }
                            .onSuccess { show("已删除 $domain 下全部 ${ids.size} 条记录") }
                            .onFailure { show(it.message.orEmpty()) }
                    }
                }) { Text("一键删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteGroup = null }) { Text("取消") } },
        )
    }

    if (pendingClearAll) {
        AlertDialog(
            onDismissRequest = { pendingClearAll = false },
            title = { Text("清空书源库") },
            text = { Text("将清空本地工坊保存的全部 ${projects.size} 条书源记录。已导入阅读客户端的书源不受影响。此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingClearAll = false
                    val allIds = projects.map { it.id }
                    scope.launch {
                        runCatching { app.projects.delete(allIds) }
                            .onSuccess { show("已清空本地书源库") }
                            .onFailure { show(it.message.orEmpty()) }
                    }
                }) { Text("全部清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingClearAll = false }) { Text("取消") } },
        )
    }

    // 书源详情视图（JSON 格式展示）
    viewingProject?.let { project ->
        SourceDetailView(
            project = project,
            onBack = { viewingProject = null },
            onCopy = ::copySource,
            onImport = { importSource(it.sourceJson) },
            onShare = ::shareSource,
        )
    }
}

@Composable
private fun DomainSourceGroup(
    group: SourceGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDetail: (ProjectEntity) -> Unit,
    onCopy: (ProjectEntity) -> Unit,
    onImport: (ProjectEntity) -> Unit,
    onShare: (ProjectEntity) -> Unit,
    onDelete: (ProjectEntity) -> Unit,
    onDeleteGroup: (SourceGroup) -> Unit,
) {
    GlassCard {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(group.domain, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${group.items.size} 条记录 · 最近 ${formatTime(group.latest.updatedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = { onDeleteGroup(group) },
                    modifier = Modifier.padding(end = 4.dp),
                ) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "删除整组",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                    )
                }
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column {
                    HorizontalDivider()
                    group.items.forEachIndexed { index, project ->
                        if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        SourceVersionRow(
                            project = project,
                            onDetail = onDetail,
                            onCopy = onCopy,
                            onImport = onImport,
                            onShare = onShare,
                            onDelete = onDelete,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceVersionRow(
    project: ProjectEntity,
    onDetail: (ProjectEntity) -> Unit,
    onCopy: (ProjectEntity) -> Unit,
    onImport: (ProjectEntity) -> Unit,
    onShare: (ProjectEntity) -> Unit,
    onDelete: (ProjectEntity) -> Unit,
) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onDetail(project) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(project.name.ifBlank { "未命名书源" }, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                Text(project.siteUrl.ifBlank { project.id }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatTime(project.updatedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
            }
            IconButton(
                onClick = { onDelete(project) },
                modifier = Modifier.padding(start = 4.dp),
            ) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "删除书源",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                )
            }
        }

        // 4 个核心功能按钮：2x2 对称网格，整齐大方
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { onDetail(project) },
                    modifier = Modifier.weight(1f).height(38.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) {
                    Text("详情", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
                TextButton(
                    onClick = {
                        onCopy(project)
                        copied = true
                    },
                    modifier = Modifier.weight(1f).height(38.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) {
                    Text(if (copied) "已复制" else "复制源", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { onImport(project) },
                    modifier = Modifier.weight(1f).height(38.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) {
                    Text("导入至阅读", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
                TextButton(
                    onClick = { onShare(project) },
                    modifier = Modifier.weight(1f).height(38.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) {
                    Text("分享JSON", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
        }
    }
}

/**
 * 制作成功的书源详情页：格式化 JSON 展示、支持选择复制与快速导入
 */
@Composable
private fun SourceDetailView(
    project: ProjectEntity,
    onBack: () -> Unit,
    onCopy: (ProjectEntity) -> Unit,
    onImport: (ProjectEntity) -> Unit,
    onShare: (ProjectEntity) -> Unit,
) {
    BackHandler(onBack = onBack)
    val prettyJson = remember(project.sourceJson) { formatJson(project.sourceJson) }
    var copied by remember { mutableStateOf(false) }
    var wrapJson by remember { mutableStateOf(true) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        SelectionContainer {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(top = 64.dp + studioTopInset(), bottom = StudioSpacing.card + studioBottomInset())
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = StudioSpacing.card),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    project.name.ifBlank { "未命名书源" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "域名：${SourceCatalog.domainOf(project.siteUrl)} · 站点：${project.siteUrl.ifBlank { "无" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "更新时间：${formatTime(project.updatedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(StudioSpacing.medium)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(StudioSpacing.medium),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = {
                                onCopy(project)
                                copied = true
                            },
                            modifier = Modifier.weight(1f).height(48.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Text(if (copied) "已复制" else "复制源", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        }
                        Button(
                            onClick = { onImport(project) },
                            modifier = Modifier.weight(1f).height(48.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Text("导入至阅读", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Button(
                        onClick = { onShare(project) },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text("分享JSON", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("书源规则 JSON：", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { wrapJson = !wrapJson }) {
                        Text(if (wrapJson) "自动换行: 开" else "自动换行: 关", style = MaterialTheme.typography.labelSmall)
                    }
                }
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    if (wrapJson) {
                        Box(Modifier.fillMaxWidth().padding(StudioSpacing.large)) {
                            Text(
                                text = prettyJson,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                softWrap = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(StudioSpacing.large),
                        ) {
                            Text(
                                text = prettyJson,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                softWrap = false,
                            )
                        }
                    }
                }
            }
        }
        GlassTopBar(
            "书源详情",
            onBack = onBack,
            actions = {
                TextButton(onClick = { wrapJson = !wrapJson }) {
                    Text(if (wrapJson) "换行" else "单行")
                }
            },
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

private fun formatJson(json: String): String {
    return runCatching {
        val trimmed = json.trim()
        if (trimmed.startsWith("{")) {
            JSONObject(trimmed).toString(2)
        } else if (trimmed.startsWith("[")) {
            JSONArray(trimmed).toString(2)
        } else {
            json
        }
    }.getOrDefault(json)
}

/** 分享用文件名：优先书源名，剥离文件系统非法字符，过长截断；空名退化为 id */
private fun exportFileName(project: ProjectEntity): String {
    val raw = project.name.ifBlank { project.id }.ifBlank { "booksource" }
    val safe = raw.replace(Regex("[/\\\\:*?\"<>|\\x00-\\x1f]"), "_").trim().trim('.').take(64)
    return safe.ifBlank { "booksource" }
}

private fun formatTime(value: Long): String = DateFormat.getDateTimeInstance().format(Date(value))
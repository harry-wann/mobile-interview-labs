package tw.harry.labs.kit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * App 的 MainActivity 繼承這個，提供 practice 與 solution 的所有區。
 *
 * 啟動參數（截圖檢查用）：
 *   adb shell am start -n tw.harry.labs/.MainActivity --es open Kotlin03Coroutines/L05 --es source solution
 */
abstract class LabRootActivity : ComponentActivity() {
    abstract val practice: List<LabSection>
    abstract val solution: List<LabSection>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val open = intent.getStringExtra("open")
        val source = intent.getStringExtra("source")?.let { s -> Source.entries.firstOrNull { it.name.equals(s, true) } }
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    LabRoot(practice, solution, open, source ?: Source.PRACTICE)
                }
            }
        }
    }
}

private fun findUnit(code: String, sections: List<LabSection>): LabUnit? =
    sections.firstNotNullOfOrNull { s -> s.units.firstOrNull { it.code == code } }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabRoot(practice: List<LabSection>, solution: List<LabSection>, open: String?, initialSource: Source) {
    // 目前畫面："" 首頁、"Kotlin03Coroutines" 單元、"Kotlin03Coroutines/L05" 演練
    var route by rememberSaveable { mutableStateOf(open ?: "") }
    val unitCode = route.substringBefore('/')
    val labId = route.substringAfter('/', "")
    val unit = if (unitCode.isEmpty()) null else findUnit(unitCode, solution)
    val lab = unit?.labs?.firstOrNull { it.id == labId }

    BackHandler(enabled = route.isNotEmpty()) {
        route = if (labId.isNotEmpty()) unitCode else ""
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            lab != null -> "${lab.id} ${lab.title}"
                            unit != null -> "${unit.code} ${unit.title}"
                            else -> "Mobile Labs"
                        },
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    if (route.isNotEmpty()) {
                        TextButton(onClick = { route = if (labId.isNotEmpty()) unitCode else "" }) { Text("‹ 返回") }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                lab != null && unit != null -> key(route) {
                    LabDetail(
                        practice = findUnit(unit.code, practice)?.labs?.firstOrNull { it.id == lab.id },
                        solution = lab,
                        initialSource = initialSource,
                    )
                }
                unit != null -> UnitScreen(unit) { route = "${unit.code}/${it.id}" }
                else -> HomeScreen(solution, onOpen = { route = it.code }, onOpenLab = { code, labId -> route = "$code/$labId" })
            }
        }
    }
}

@Composable
private fun HomeScreen(sections: List<LabSection>, onOpen: (LabUnit) -> Unit, onOpenLab: (String, String) -> Unit) {
    // 「四大主題」：記憶體、效能、架構、UI/UX 四項各自對應的演練（labs/topics.yaml）；「依區」：技術分區
    var byFocus by rememberSaveable { mutableStateOf(true) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                "每個演練都有「練習」和「解答」兩份程式碼。先打開 practice 資料夾裡的檔案，照 TODO 寫出 Bug 版和修正版，回到這裡執行；自動檢查會告訴你寫對了沒有。卡住時切到「解答」執行，或打開 solution 資料夾裡同名的檔案對照。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            Box(Modifier.padding(horizontal = 16.dp)) {
                Segmented(listOf(true, false), byFocus, { if (it) "四大主題" else "依區" }) { byFocus = it }
            }
        }
        if (byFocus) {
            JdFocus.topics.forEachIndexed { index, topic ->
                item(key = "topic-${topic.id}") {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)) {
                        Text("${index + 1}　${topic.title}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text(topic.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                topic.groups.forEachIndexed { gi, group ->
                    item(key = "group-${topic.id}-$gi") {
                        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
                            Text(group.title, fontWeight = FontWeight.SemiBold)
                            if (group.note != null) {
                                Text(group.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    items(group.labs, key = { "lab-${it.code}-${it.labId}" }) { ref ->
                        val unit = findUnit(ref.code, sections)
                        val lab = unit?.labs?.firstOrNull { it.id == ref.labId }
                        if (lab != null) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenLab(ref.code, ref.labId) }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                            ) {
                                Text(lab.title)
                                Text("${ref.code} ${ref.labId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
            return@LazyColumn
        }
        for (section in sections) {
            item(key = section.id) {
                Text(
                    section.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            items(section.units, key = { it.code }) { unit ->
                val empty = unit.labs.isEmpty()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !empty) { onOpen(unit) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(
                        "${unit.code}　${unit.title}",
                        fontWeight = FontWeight.SemiBold,
                        color = if (empty) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        if (empty) "還沒有演練" else "${unit.labs.size} 個演練",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun UnitScreen(unit: LabUnit, onOpen: (Lab) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(unit.labs, key = { it.id }) { lab ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(lab) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text("${lab.id}　${lab.title}", fontWeight = FontWeight.SemiBold)
                Text(lab.question, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun <T> Segmented(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) { Text(label(option)) }
        }
    }
}

@Composable
private fun LabDetail(practice: Lab?, solution: Lab, initialSource: Source) {
    var source by rememberSaveable { mutableStateOf(if (practice == null) Source.SOLUTION else initialSource) }
    var variant by rememberSaveable { mutableStateOf(Variant.BUG) }
    val log = remember { LabLog() }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var showGuide by rememberSaveable { mutableStateOf(true) }
    var showHint by rememberSaveable { mutableStateOf(false) }
    // 每個「來源 × 版本」最近一次的檢查結果："" = 符合預期，其他 = 還差什麼；沒有 = 還沒執行
    val results = remember { mutableStateMapOf<String, String>() }
    val lab = if (source == Source.PRACTICE) practice else solution

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Segmented(Source.entries, source, { it.title }) { job?.cancel(); source = it }
            Segmented(Variant.entries, variant, { it.title }) { job?.cancel(); variant = it }
        }
        if (lab != null) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Variant.entries.forEach { v ->
                    val r = results["${source.name}-${v.name}"]
                    val text = when {
                        lab.run == null && lab.demo == null -> "${v.title}：在電腦上跑單元測試"
                        lab.check(v) == null -> "${v.title}：看畫面判斷"
                        r == null -> "${v.title}：還沒執行"
                        r.isEmpty() -> "${v.title}：✓ 符合預期"
                        else -> "${v.title}：✗ $r"
                    }
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        color = when {
                            r == null -> MaterialTheme.colorScheme.onSurfaceVariant
                            r.isEmpty() -> Color(0xFF2E7D32)
                            else -> Color(0xFFE65100)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (lab == null) {
                Text("練習檔案還沒建立。請先切到「解答」。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            if (lab.bugCrashes && variant == Variant.BUG) {
                Text(
                    "⚠ 這個 Bug 版寫對了會讓 App 當掉。打開 Logcat 看例外和出錯的那一行，再重新執行 App。",
                    color = Color(0xFFE65100),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // 需要操作畫面的演練，操作區放最上面
            lab.demo?.let { demo ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                        .padding(10.dp),
                ) {
                    key(source, variant) { demo(variant, log) }
                }
            }
            TextButton(onClick = { showGuide = !showGuide }) { Text(if (showGuide) "▾ 題目與步驟" else "▸ 題目與步驟") }
            if (showGuide) {
                Text(lab.question, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(lab.goal)
                lab.steps.forEachIndexed { i, step -> Text("${i + 1}. $step", style = MaterialTheme.typography.bodyMedium) }
                Text(
                    "檔案：${source.name.lowercase()}/${lab.file}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { showHint = !showHint }) { Text(if (showHint) "▾ 提示：寫對了會看到什麼" else "▸ 提示：寫對了會看到什麼") }
                if (showHint) {
                    Text("Bug 版：${lab.expectBug}", style = MaterialTheme.typography.bodyMedium)
                    Text("修正版：${lab.expectFixed}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val running = job?.isActive == true
            lab?.run?.let { run ->
                Button(
                    enabled = !running,
                    onClick = {
                        val v = variant
                        val s = source
                        val check = lab.check(v)
                        job = scope.launch {
                            log("──── ${s.title}・${v.title} ────")
                            val mark = log.mark()
                            try {
                                run(v, log)
                                if (check != null) {
                                    val reason = check(log.textsSince(mark))
                                    results["${s.name}-${v.name}"] = reason ?: ""
                                    log(if (reason == null) "✓ 符合預期" else "✗ 還沒符合預期：$reason")
                                }
                            } finally {
                                job = null
                            }
                        }
                    },
                ) { Text("執行${source.title}的${variant.title}") }
            }
            OutlinedButton(onClick = { log.clear() }) { Text("清除 log") }
            Spacer(Modifier.weight(1f))
            if (running) CircularProgressIndicator(Modifier.height(24.dp))
        }
        LogConsole(log, Modifier.fillMaxWidth().height(200.dp))
    }
}

/** log 主控台：等寬字、自動捲到最新一行 */
@Composable
fun LogConsole(log: LabLog, modifier: Modifier = Modifier) {
    val lines by log.lines.collectAsState()
    val state = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) state.scrollToItem(lines.size - 1)
    }
    SelectionContainer(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        LazyColumn(state = state, modifier = Modifier.padding(8.dp)) {
            items(lines, key = { it.id }) { line ->
                Text(
                    "+${line.ms}ms [${line.thread}] ${line.text}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = when {
                        line.text.startsWith("────") -> MaterialTheme.colorScheme.primary
                        line.text.startsWith("✓") -> Color(0xFF2E7D32)
                        line.text.startsWith("✗") -> Color(0xFFE65100)
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

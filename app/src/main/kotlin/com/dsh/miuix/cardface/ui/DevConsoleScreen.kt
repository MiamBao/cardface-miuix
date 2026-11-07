package com.dsh.miuix.cardface.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dsh.miuix.cardface.core.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 诊断终端：以 root 执行 adb shell 风格的命令，用于排查卡包 hook / 图片不生效等问题。
 *
 * 这里只保留排查最常用的几个动作；需要别的命令可以直接在输入框里执行。
 */
@Composable
fun DevConsoleScreen(
    innerPadding: PaddingValues,
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var command by remember { mutableStateOf("id; getenforce") }
    var output by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun run(cmd: String) {
        if (running || cmd.isBlank()) return
        command = cmd
        running = true
        output = "> $cmd\n\n"
        scope.launch {
            val result = withContext(Dispatchers.IO) { RootShell.exec(cmd, timeoutMs = 15000) }
            output = buildString {
                append("> ").append(cmd).append("\n\n")
                if (result.rootUnavailable) append("[没有 root 权限]\n")
                if (result.timedOut) append("[超时]\n")
                append(result.stdout)
                if (result.stderr.isNotBlank()) {
                    append("\n[stderr]\n").append(result.stderr)
                }
                append("\n[exit=").append(result.exitCode).append(']')
            }
            running = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = innerPadding.calculateTopPadding()),
    ) {
        SmallTopAppBar(
            title = "诊断终端",
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(MiuixIcons.Back, contentDescription = "返回")
                }
            },
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            TextField(
                value = command,
                onValueChange = { command = it },
                label = "su -c 命令",
                modifier = Modifier.fillMaxWidth(),
                enabled = !running,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(text = if (running) "执行中…" else "运行", onClick = { run(command) })
                TextButton(text = "清空", onClick = { output = "" })
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TextButton(
                    text = "重启卡包",
                    onClick = {
                        val pkg = com.dsh.miuix.cardface.core.WalletApps.primary(context)?.packageName
                        run("am force-stop ${pkg ?: "com.miui.tsmclient"}; echo done")
                    },
                )
                TextButton(
                    text = "清卡包图片缓存",
                    onClick = {
                        run(
                            "rm -rf /proc/1/root/data/data/com.miui.tsmclient/cache/image_manager_disk_cache* " +
                                "/proc/1/root/data/data/com.miui.tsmclient/cache/image_cache*; echo done",
                        )
                    },
                )
                TextButton(
                    text = "hook 是否加载",
                    onClick = {
                        run("grep -a cardface \$(ls -t /proc/1/root/data/adb/lspd/log/modules_*.log 2>/dev/null | head -1) | tail -n 20")
                    },
                )
                TextButton(
                    text = "root 检查",
                    onClick = { run("id; which su; getenforce") },
                )
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                insideMargin = PaddingValues(14.dp),
            ) {
                Text(
                    text = output.ifBlank { "输出会显示在这里" },
                    style = MiuixTheme.textStyles.footnote2,
                    fontFamily = FontFamily.Monospace,
                    overflow = TextOverflow.Visible,
                )
            }
        }
    }
}

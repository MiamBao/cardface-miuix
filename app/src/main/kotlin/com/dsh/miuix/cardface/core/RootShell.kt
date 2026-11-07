package com.dsh.miuix.cardface.core

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * `su -c` 的薄封装：超时、返回码、输出、无 root 处理。
 *
 * 不依赖任何 Android API，因此被 hook 的卡包进程里也能直接用（兼容模式复制图片）。
 * 约定：任何流程都必须有「不需要 root」的默认路径，root 只做兜底，失败不抛异常。
 */
object RootShell {

    data class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean = false,
        /** 系统里没有 su 可执行文件（未 root）。 */
        val rootUnavailable: Boolean = false,
    ) {
        val ok: Boolean get() = !timedOut && !rootUnavailable && exitCode == 0

        /** su 存在但被用户拒绝（或 su 不可用）。 */
        val isRootDenied: Boolean get() = rootUnavailable
    }

    private val SU_CANDIDATES = listOf("su", "/system/bin/su", "/system/xbin/su", "/sbin/su")

    @Volatile
    private var suPath: String? = null

    @Volatile
    private var availability: Boolean? = null

    /** 是否可用 root：执行一次 `su -c id` 并缓存结果（可能会弹一次授权框）。 */
    fun isAvailable(): Boolean {
        availability?.let { return it }
        val result = runSu("id", timeoutMs = 4000)
        val available = result.ok && result.stdout.contains("uid=0")
        availability = available
        return available
    }

    fun resetAvailabilityCache() {
        availability = null
        suPath = null
    }

    /** 执行一条 shell 命令（会作为 `su -c <command>` 的脚本解释）。 */
    fun exec(command: String, timeoutMs: Long = 5000): Result {
        if (command.isBlank()) return Result(0, "", "")
        return runSu(command, timeoutMs)
    }

    /** 复制文件到目标目录并 chmod 644；目标已存在时跳过复制。路径会自动加引号。 */
    fun copyIntoDir(
        sourcePath: String,
        destDir: String,
        fileName: String,
        mode: String = "644",
        timeoutMs: Long = 5000,
    ): Result {
        val dest = "${destDir.trimEnd('/')}/$fileName"
        val command = buildString {
            append("mkdir -p ").append(quote(destDir.trimEnd('/')))
            append(" && { [ -f ").append(quote(dest)).append(" ] || cp ").append(quote(sourcePath))
            append(' ').append(quote(dest)).append("; }")
            append(" && chmod ").append(mode).append(' ').append(quote(dest))
        }
        return runSu(command, timeoutMs)
    }

    fun mkdirs(path: String, timeoutMs: Long = 5000): Result =
        runSu("mkdir -p ${quote(path)}", timeoutMs)

    fun chmod(path: String, mode: String = "644", timeoutMs: Long = 5000): Result =
        runSu("chmod $mode ${quote(path)}", timeoutMs)

    fun deleteFiles(paths: List<String>, timeoutMs: Long = 5000): Result {
        if (paths.isEmpty()) return Result(0, "", "")
        val script = paths.joinToString(" ") { quote(it) }
        return runSu("rm -f $script", timeoutMs)
    }

    /** 删除目录下匹配 pattern 的文件（pattern 由调用方保证安全，例如 cardface.method*.txt）。 */
    fun deleteGlob(dir: String, pattern: String, timeoutMs: Long = 5000): Result =
        runSu("rm -f ${quote(dir.trimEnd('/'))}/$pattern", timeoutMs)

    fun forceStop(packageName: String, timeoutMs: Long = 5000): Result =
        runSu("am force-stop ${quote(packageName)}", timeoutMs)

    fun listFiles(dir: String, timeoutMs: Long = 5000): Result =
        runSu("ls -1 ${quote(dir.trimEnd('/'))} 2>/dev/null", timeoutMs)

    // ---------- 内部 ----------

    private fun runSu(command: String, timeoutMs: Long): Result {
        val errors = StringBuilder()
        for (candidate in resolveCandidates()) {
            val result = runProcess(candidate, command, timeoutMs)
            if (result !== null) {
                suPath = candidate
                return result
            }
            errors.append(candidate).append(": not executable; ")
        }
        return Result(
            exitCode = -1,
            stdout = "",
            stderr = errors.toString().ifEmpty { "su not found" },
            rootUnavailable = true,
        )
    }

    private fun resolveCandidates(): List<String> {
        val cached = suPath
        if (cached != null) return listOf(cached)
        val found = SU_CANDIDATES.filter { candidate ->
            candidate == "su" || File(candidate).canExecute()
        }
        return if (found.isEmpty()) SU_CANDIDATES else found
    }

    /** @return null 表示进程都没起来（su 不存在 / 无执行权限）。 */
    private fun runProcess(su: String, command: String, timeoutMs: Long): Result? {
        var process: Process? = null
        try {
            process = ProcessBuilder(su, "-c", command).start()
            val out = StringBuilder()
            val err = StringBuilder()
            val outReader = drain(process.inputStream.bufferedReader(), out)
            val errReader = drain(process.errorStream.bufferedReader(), err)
            val finished = process.waitFor(timeoutMs.coerceAtLeast(1000), TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                outReader.join(200)
                errReader.join(200)
                return Result(-1, out.toString(), err.toString(), timedOut = true)
            }
            outReader.join(400)
            errReader.join(400)
            return Result(process.exitValue(), out.toString(), err.toString())
        } catch (_: java.io.IOException) {
            return null
        } catch (t: Throwable) {
            return Result(-1, "", t.toString())
        } finally {
            try {
                process?.inputStream?.close()
                process?.errorStream?.close()
                process?.outputStream?.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun drain(reader: BufferedReader, sink: StringBuilder): Thread {
        val thread = Thread {
            try {
                reader.useLines { lines -> lines.forEach { sink.append(it).append('\n') } }
            } catch (_: Throwable) {
            }
        }
        thread.isDaemon = true
        thread.start()
        return thread
    }

    /** 单引号安全引用，供 su -c 的 shell 使用。 */
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

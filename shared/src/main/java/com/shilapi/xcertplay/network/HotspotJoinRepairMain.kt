package com.shilapi.xcertplay.network

import android.os.Process
import java.io.File
import java.io.IOException
import kotlin.system.exitProcess

/** Privileged entry point. Prints ONLY the protocol result: no exception text, parcels or credentials. */
object HotspotJoinRepairMain {
    @JvmStatic fun main(args: Array<String>) {
        // A stuck Binder call must not leave a privileged helper running indefinitely.
        Thread({ Thread.sleep(25_000); exitProcess(2) }, "hotspot-helper-deadline")
            .apply { isDaemon = true; start() }
        val result = try { execute(args) } catch (_: HotspotJoinJournal.FirmwareChanged) {
            HotspotJoinTransaction.Result(HotspotJoinTransaction.Status.FIRMWARE_CHANGED, rollback = true)
        } catch (_: IOException) {
            HotspotJoinTransaction.Result(HotspotJoinTransaction.Status.STORAGE_FAILED, rollback = true)
        } catch (_: Throwable) {
            // Unknown could include a Binder write that took effect before throwing. Keep the journal.
            HotspotJoinTransaction.Result(HotspotJoinTransaction.Status.RECOVERY_REQUIRED, rollback = true)
        }
        println("${HotspotJoinRepair.HEADER}|${result.status}|${result.token ?: "-"}|${if (result.rollback) 1 else 0}")
        exitProcess(0)
    }

    private fun execute(args: Array<String>): HotspotJoinTransaction.Result {
        val uid = Process.myUid()
        require(uid == 2000 || uid == 0)
        require(args.size == 4)
        val (packageName, action, request, token) = args
        require(packageName.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+")))
        require(action in setOf("check", "apply", "restore", "cancel") && HotspotJoinRepair.validToken(request))
        require(token == "-" || HotspotJoinRepair.validToken(token))
        // 精简移植：修复事务依赖 Android 13+ 的 Wi-Fi 框架接口（SoftApConfiguration 等），
        // 目标平台 API 19 恒定不支持，直接返回 UNSUPPORTED。
        return HotspotJoinTransaction.Result(HotspotJoinTransaction.Status.UNSUPPORTED)
        // 精简移植：android.system.Os 为 API 21+，降级为 java.io.File 等价实现。
        // 原 Os.umask(0x3f) 无法在 API 19 设置 umask，新建文件权限交由系统默认。
        val directory = File("/data/local/tmp/diplay-hotspot-join-$packageName")
        if (!directory.exists() && !directory.mkdirs()) throw IOException()
        // 原 lstat/S_ISDIR/st_uid/st_mode 校验降级：API 19 无法读取文件属主与完整权限位，
        // 仅校验目录类型与读写执行权限；符号链接风险改用规范路径校验规避。
        if (!directory.isDirectory || !directory.canRead() || !directory.canWrite() ||
            !directory.canExecute())
            throw IOException()
        for (file in directory.listFiles() ?: throw IOException()) {
            if (!file.isFile || !file.canRead() || !file.canWrite() ||
                file.canonicalPath != file.path)
                throw IOException() // Never follow foreign/symlinked backup or lock paths.
        }
        val cancellation = File(directory, "cancel-$request")
        if (action == "cancel") {
            cancellation.writeText("")
            return HotspotJoinTransaction.Result(HotspotJoinTransaction.Status.CANCELLED)
        }
        val deadline = System.nanoTime() + 20_000_000_000L
        try {
            return HotspotJoinLock.run(directory) {
                val port = try { HotspotJoinPlatform.connect() }
                    catch (_: Exception) { return@run HotspotJoinTransaction.Result(HotspotJoinTransaction.Status.UNSUPPORTED) }
                val journal = HotspotJoinJournal(directory, port.firmware(), port.codec) {
                    // 精简移植：原 Os.open(O_NOFOLLOW)/fstat/S_ISDIR/fsync 降级：API 19 无法对目录
                    // fsync，仅用规范路径校验目录真实存在且非符号链接。
                    for (durableDirectory in listOf(directory.parentFile, directory)) {
                        if (!durableDirectory.isDirectory ||
                            durableDirectory.canonicalPath != durableDirectory.path) throw IOException()
                    }
                }
                journal.load() // Firmware validation before decoding/any repair action.
                val transaction = HotspotJoinTransaction(port, journal) {
                    cancellation.exists() || System.nanoTime() >= deadline || Thread.currentThread().isInterrupted
                }
                when (action) {
                    "check" -> transaction.prepare()
                    "apply" -> transaction.apply(token)
                    "restore" -> transaction.restore(token)
                    else -> throw IllegalArgumentException()
                }
            } ?: HotspotJoinTransaction.Result(HotspotJoinTransaction.Status.BUSY)
        } finally { cancellation.delete() }
    }
}

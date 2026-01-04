/*
 * Copyright (C) 2014-2020 Arpit Khurana <arpitkh96@gmail.com>, Vishal Nehra <vishalmeham2@gmail.com>,
 * Emmanuel Messulam<emmanuelbendavid@gmail.com>, Raymond Lai <airwave209gt at gmail.com> and Contributors.
 *
 * This file is part of Amaze File Manager.
 *
 * Amaze File Manager is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.amaze.filemanager.filesystem.root.base

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.preference.PreferenceManager
import com.amaze.filemanager.application.AppConfig
import com.amaze.filemanager.exceptions.ShellCommandInvalidException
import com.amaze.filemanager.fileoperations.exceptions.ShellNotRunningException
import com.amaze.filemanager.ui.activities.MainActivity
import com.amaze.filemanager.ui.fragments.preferencefragments.PreferencesConstants
import com.topjohnwu.superuser.Shell
import rikka.shizuku.Shizuku

enum class RootBackend {
    ROOT_SHELL,
    SHIZUKU,
    ADB_TCP,
}

data class RootCommandResult(val code: Int, val out: List<String>, val err: List<String>)

data class BackendStatus(val available: Boolean, val message: String? = null)

object RootCommandBackendManager {
    private const val SHIZUKU_PERMISSION_REQUEST_CODE = 1945

    private val mainHandler = Handler(Looper.getMainLooper())

    private val applicationContext: Context
        get() = AppConfig.getInstance()

    private val sharedPreferences
        get() = PreferenceManager.getDefaultSharedPreferences(applicationContext)

    private var shizukuPermissionCallback: ((Boolean, String?) -> Unit)? = null

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != SHIZUKU_PERMISSION_REQUEST_CODE) return@OnRequestPermissionResultListener
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            val message =
                if (granted) {
                    null
                } else {
                    applicationContext.getString(
                        com.amaze.filemanager.R.string.root_backend_shizuku_permission_required,
                    )
                }
            postShizukuPermissionResult(granted, message)
        }

    fun selectedBackend(): RootBackend =
        parseBackend(
            sharedPreferences.getString(
                PreferencesConstants.PREFERENCE_ROOT_BACKEND,
                RootBackend.ROOT_SHELL.name,
            ),
        )

    fun parseBackend(value: Any?): RootBackend =
        enumValues<RootBackend>().firstOrNull { it.name == (value as? String) }
            ?: RootBackend.ROOT_SHELL

    fun updateBackend(newValue: Any?) {
        val next = parseBackend(newValue)
        sharedPreferences.edit().putString(PreferencesConstants.PREFERENCE_ROOT_BACKEND, next.name)
            .apply()
    }

    fun hasShizukuPermission(): Boolean =
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    fun requestShizukuPermission(onResult: (Boolean, String?) -> Unit) {
        if (!Shizuku.pingBinder()) {
            mainHandler.post {
                onResult(
                    false,
                    applicationContext.getString(
                        com.amaze.filemanager.R.string.root_backend_missing_shizuku,
                    ),
                )
            }
            return
        }
        if (hasShizukuPermission()) {
            mainHandler.post { onResult(true, null) }
            return
        }

        shizukuPermissionCallback = onResult
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
    }

    fun capabilityFor(backend: RootBackend = selectedBackend()): BackendStatus =
        when (backend) {
            RootBackend.ROOT_SHELL -> {
                val shell = Shell.getShell()
                val isRoot = shell.isRoot
                if (isRoot) {
                    BackendStatus(true, null)
                } else {
                    BackendStatus(
                        false,
                        applicationContext.getString(
                            com.amaze.filemanager.R.string.root_backend_missing_root_shell,
                        ),
                    )
                }
            }
            RootBackend.SHIZUKU -> {
                if (!Shizuku.pingBinder()) {
                    BackendStatus(
                        false,
                        applicationContext.getString(
                            com.amaze.filemanager.R.string.root_backend_missing_shizuku,
                        ),
                    )
                } else if (!hasShizukuPermission()) {
                    BackendStatus(
                        false,
                        applicationContext.getString(
                            com.amaze.filemanager.R.string.root_backend_shizuku_permission_required,
                        ),
                    )
                } else {
                    BackendStatus(
                        true,
                        applicationContext.getString(
                            com.amaze.filemanager.R.string.root_backend_status_ready,
                        ),
                    )
                }
            }
            RootBackend.ADB_TCP -> {
                val host = adbHost()
                if (host.isBlank()) {
                    BackendStatus(
                        false,
                        applicationContext.getString(
                            com.amaze.filemanager.R.string.root_backend_missing_adb_tcp,
                        ),
                    )
                } else if (!hasAdbClient()) {
                    BackendStatus(
                        false,
                        applicationContext.getString(
                            com.amaze.filemanager.R.string.root_backend_missing_adb_binary,
                        ),
                    )
                } else {
                    BackendStatus(true, "$host:${adbPort()}")
                }
            }
        }

    fun describeSelectedBackend(context: Context? = null): String {
        val backend = selectedBackend()
        val status = capabilityFor(backend)
        val name = when (backend) {
            RootBackend.ROOT_SHELL -> context?.getString(
                com.amaze.filemanager.R.string.root_backend_root_shell,
            ) ?: backend.name
            RootBackend.SHIZUKU -> context?.getString(
                com.amaze.filemanager.R.string.root_backend_shizuku,
            ) ?: backend.name
            RootBackend.ADB_TCP -> context?.getString(
                com.amaze.filemanager.R.string.root_backend_adb_tcp,
            ) ?: backend.name
        }
        val statusLabel =
            if (status.available) {
                status.message
                    ?: context?.getString(com.amaze.filemanager.R.string.root_backend_status_ready)
                    ?: "ready"
            } else {
                status.message
                    ?: context?.getString(com.amaze.filemanager.R.string.root_backend_status_missing)
                    ?: "missing"
            }
        return "$name • $statusLabel"
    }

    @Throws(ShellNotRunningException::class)
    fun runCommand(cmd: String): RootCommandResult {
        val backend = selectedBackend()
        val status = capabilityFor(backend)
        if (!status.available) {
            throw ShellNotRunningException(
                status.message
                    ?: applicationContext.getString(
                        com.amaze.filemanager.R.string.root_backend_status_missing,
                    ),
            )
        }
        return when (backend) {
            RootBackend.ROOT_SHELL -> runWithRootShell(cmd)
            RootBackend.SHIZUKU -> runWithShizuku(cmd)
            RootBackend.ADB_TCP -> runWithAdbShell(cmd)
        }
    }

    private fun postShizukuPermissionResult(granted: Boolean, message: String?) {
        val callback = shizukuPermissionCallback ?: return
        shizukuPermissionCallback = null
        mainHandler.post { callback(granted, message) }
    }

    private fun runWithRootShell(cmd: String): RootCommandResult =
        Shell.su(cmd).exec().toRootResult()

    private fun runWithAdbShell(cmd: String): RootCommandResult {
        val target = adbTarget()
        if (!hasAdbClient()) {
            throw ShellNotRunningException(
                applicationContext.getString(
                    com.amaze.filemanager.R.string.root_backend_missing_adb_binary,
                ),
            )
        }
        val connectResult = Shell.cmd("adb", "connect", target).exec()
        if (connectResult.code != 0) {
            val combinedOutput = (connectResult.err + connectResult.out).joinToString("\n")
            throw ShellNotRunningException(combinedOutput.ifBlank { "Unable to connect to $target" })
        }
        return Shell.cmd("adb", "-s", target, "shell", cmd).exec().toRootResult()
    }

    private fun runWithShizuku(cmd: String): RootCommandResult {
        if (!Shizuku.pingBinder()) {
            throw ShellNotRunningException(
                applicationContext.getString(
                    com.amaze.filemanager.R.string.root_backend_missing_shizuku,
                ),
            )
        }
        if (!hasShizukuPermission()) {
            throw ShellNotRunningException(
                applicationContext.getString(
                    com.amaze.filemanager.R.string.root_backend_shizuku_permission_required,
                ),
            )
        }
        return try {
            val process = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
            val stdout = process.inputStream.bufferedReader().use { reader -> reader.readLines() }
            val stderr = process.errorStream.bufferedReader().use { reader -> reader.readLines() }
            val code = process.waitFor()
            RootCommandResult(code, stdout, stderr)
        } catch (e: Exception) {
            throw ShellNotRunningException(e.message ?: "Shizuku command failed")
        }
    }

    private fun adbHost(): String =
        sharedPreferences.getString(
            PreferencesConstants.PREFERENCE_ROOT_ADB_HOST,
            "",
        ).orEmpty()

    private fun adbPort(): String =
        sharedPreferences.getString(
            PreferencesConstants.PREFERENCE_ROOT_ADB_PORT,
            "5555",
        ).orEmpty()

    private fun adbTarget(): String {
        val host = adbHost()
        if (host.isBlank()) {
            throw ShellNotRunningException(
                applicationContext.getString(
                    com.amaze.filemanager.R.string.root_backend_missing_adb_tcp,
                ),
            )
        }
        return "$host:${adbPort()}"
    }

    private fun hasAdbClient(): Boolean = Shell.cmd("command", "-v", "adb").exec().code == 0

    private fun Shell.Result.toRootResult(): RootCommandResult =
        RootCommandResult(code, out, err)
}

open class IRootCommand {
    /**
     * Runs the command and stores output in a list. The listener is set on the handler thread [ ]
     * [MainActivity.handlerThread] thus any code run in callback must be thread safe. Command is run
     * from the root context (u:r:SuperSU0)
     *
     * @param cmd the command
     * @return a list of results. Null only if the command passed is a blocking call or no output is
     * there for the command passed
     */
    @Throws(ShellNotRunningException::class, ShellCommandInvalidException::class)
    fun runShellCommandToList(cmd: String): List<String> {
        var interrupt = false
        var errorCode: Int = -1
        // callback being called on a background handler thread
        val commandResult = runShellCommand(cmd)
        if (commandResult.code in 1..127) {
            interrupt = true
            errorCode = commandResult.code
        }
        val result = commandResult.out
        if (interrupt) {
            throw ShellCommandInvalidException("$cmd , error code - $errorCode")
        }
        return result
    }

    /**
     * Command is run from the root context (u:r:SuperSU0)
     *
     * @param cmd the command
     */
    @Throws(ShellNotRunningException::class)
    fun runShellCommand(cmd: String): RootCommandResult {
        return RootCommandBackendManager.runCommand(cmd)
    }
}

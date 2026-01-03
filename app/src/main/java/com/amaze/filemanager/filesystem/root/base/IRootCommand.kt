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

import com.amaze.filemanager.exceptions.ShellCommandInvalidException
import com.amaze.filemanager.fileoperations.exceptions.ShellNotRunningException
import com.amaze.filemanager.application.AppConfig
import com.amaze.filemanager.ui.fragments.preferencefragments.PreferencesConstants
import com.amaze.filemanager.ui.activities.MainActivity
import android.content.Context
import androidx.preference.PreferenceManager
import com.topjohnwu.superuser.Shell

enum class RootBackend {
    ROOT_SHELL,
    SHIZUKU,
    ADB_TCP,
}

data class BackendStatus(val available: Boolean, val message: String? = null)

object RootCommandBackendManager {
    private val applicationContext: Context
        get() = AppConfig.getInstance()

    private val sharedPreferences
        get() = PreferenceManager.getDefaultSharedPreferences(applicationContext)

    fun selectedBackend(): RootBackend =
        sharedPreferences
            .getString(
                PreferencesConstants.PREFERENCE_ROOT_BACKEND,
                RootBackend.ROOT_SHELL.name,
            )
            ?.let { value ->
                enumValues<RootBackend>().firstOrNull { it.name == value }
            } ?: RootBackend.ROOT_SHELL

    fun updateBackend(newValue: Any?) {
        val next = enumValues<RootBackend>().firstOrNull { it.name == (newValue as? String) }
            ?: RootBackend.ROOT_SHELL
        sharedPreferences.edit().putString(PreferencesConstants.PREFERENCE_ROOT_BACKEND, next.name)
            .apply()
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
            RootBackend.SHIZUKU ->
                BackendStatus(
                    false,
                    applicationContext.getString(
                        com.amaze.filemanager.R.string.root_backend_missing_shizuku,
                    ),
                )
            RootBackend.ADB_TCP -> {
                val host =
                    sharedPreferences.getString(
                        PreferencesConstants.PREFERENCE_ROOT_ADB_HOST,
                        "",
                    ).orEmpty()
                val port =
                    sharedPreferences.getString(
                        PreferencesConstants.PREFERENCE_ROOT_ADB_PORT,
                        "5555",
                    ).orEmpty()
                if (host.isBlank()) {
                    BackendStatus(
                        false,
                        applicationContext.getString(
                            com.amaze.filemanager.R.string.root_backend_missing_adb_tcp,
                        ),
                    )
                } else {
                    BackendStatus(true, "$host:$port")
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
    fun runCommand(cmd: String): Shell.Result {
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
            RootBackend.ROOT_SHELL -> Shell.su(cmd).exec()
            RootBackend.SHIZUKU ->
                throw ShellNotRunningException(
                    applicationContext.getString(
                        com.amaze.filemanager.R.string.root_backend_missing_shizuku,
                    ),
                )
            RootBackend.ADB_TCP -> {
                val host =
                    sharedPreferences.getString(
                        PreferencesConstants.PREFERENCE_ROOT_ADB_HOST,
                        "",
                    ).orEmpty()
                val port =
                    sharedPreferences.getString(
                        PreferencesConstants.PREFERENCE_ROOT_ADB_PORT,
                        "5555",
                    ).orEmpty()
                if (host.isBlank()) {
                    throw ShellNotRunningException(
                        applicationContext.getString(
                            com.amaze.filemanager.R.string.root_backend_missing_adb_tcp,
                        ),
                    )
                }
                Shell.cmd("adb", "-s", "$host:$port", "shell", cmd).exec()
            }
        }
    }
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
    fun runShellCommand(cmd: String): Shell.Result {
        return RootCommandBackendManager.runCommand(cmd)
    }
}

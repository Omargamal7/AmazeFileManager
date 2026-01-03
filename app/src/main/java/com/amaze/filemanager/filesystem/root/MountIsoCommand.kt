/*
 * Copyright (C) 2014-2024 Arpit Khurana <arpitkh96@gmail.com>, Vishal Nehra <vishalmeham2@gmail.com>,
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

package com.amaze.filemanager.filesystem.root

import com.amaze.filemanager.exceptions.ShellCommandInvalidException
import com.amaze.filemanager.fileoperations.exceptions.ShellNotRunningException
import com.amaze.filemanager.filesystem.RootHelper
import com.amaze.filemanager.filesystem.root.base.IRootCommand
import org.slf4j.LoggerFactory
import java.io.File

object MountIsoCommand : IRootCommand() {
    private val LOG = LoggerFactory.getLogger(MountIsoCommand::class.java)
    private val mountedImages: MutableMap<String, String> = mutableMapOf()

    @JvmStatic
    fun getSuggestedMountPoint(imagePath: String): String {
        val file = File(imagePath)
        val parent = file.parent ?: "/mnt"
        val nameWithoutExtension = if (file.nameWithoutExtension.isNotEmpty()) {
            file.nameWithoutExtension
        } else {
            file.name
        }
        return "$parent/${nameWithoutExtension}_mount"
    }

    @JvmStatic
    fun getMountedPath(imagePath: String): String? {
        mountedImages[imagePath]?.let { return it }
        return try {
            val loopDevice = findLoopDevice(imagePath) ?: return null
            val mountPoint = findMountPoint(loopDevice)
            mountPoint?.also { mountedImages[imagePath] = it }
        } catch (e: Exception) {
            LOG.error("Unable to resolve mount status for {}", imagePath, e)
            null
        }
    }

    @JvmStatic
    @Throws(ShellNotRunningException::class)
    fun supportsLoopDevices(): Boolean {
        return try {
            runShellCommand(
                "command -v losetup >/dev/null 2>&1 && " +
                    "(test -e /dev/loop-control || ls /dev/block/loop* >/dev/null 2>&1)",
            ).code == 0
        } catch (e: ShellNotRunningException) {
            throw e
        } catch (e: Exception) {
            LOG.error("Loop device support check failed", e)
            false
        }
    }

    @JvmStatic
    @Throws(ShellNotRunningException::class)
    fun mountImage(
        imagePath: String,
        mountPoint: String,
    ): String? {
        val sanitizedImagePath = RootHelper.getCommandLineString(imagePath)
        val sanitizedMountPoint = RootHelper.getCommandLineString(mountPoint)

        return try {
            val prepareResult =
                runShellCommand(
                    "mkdir -p \"$sanitizedMountPoint\" && chmod 755 \"$sanitizedMountPoint\"",
                )

            if (prepareResult.code != 0) {
                LOG.error("Failed to prepare mount point {} (code {})", mountPoint, prepareResult.code)
                return null
            }

            val mountResult =
                runShellCommand(
                    "mount -o loop \"$sanitizedImagePath\" \"$sanitizedMountPoint\"",
                )
            if (mountResult.code == 0) {
                val resolvedMountPoint = getMountedPath(imagePath) ?: mountPoint
                mountedImages[imagePath] = resolvedMountPoint
                return resolvedMountPoint
            }
            LOG.error(
                "Failed to mount image {} at {} (code {})",
                imagePath,
                mountPoint,
                mountResult.code,
            )
            null
        } catch (e: ShellCommandInvalidException) {
            LOG.error("Invalid mount command for {}", imagePath, e)
            null
        }
    }

    @JvmStatic
    @Throws(ShellNotRunningException::class)
    fun unmountImage(imagePath: String): Boolean {
        val mountPoint = getMountedPath(imagePath) ?: return false
        val sanitizedMountPoint = RootHelper.getCommandLineString(mountPoint)

        return try {
            val unmountResult = runShellCommand("umount \"$sanitizedMountPoint\"")
            if (unmountResult.code != 0) {
                LOG.error(
                    "Failed to unmount image {} from {} (code {})",
                    imagePath,
                    mountPoint,
                    unmountResult.code,
                )
                return false
            }
            detachLoopDevice(imagePath)
            mountedImages.remove(imagePath)
            true
        } catch (e: ShellCommandInvalidException) {
            LOG.error("Invalid unmount command for {}", imagePath, e)
            false
        }
    }

    private fun findLoopDevice(imagePath: String): String? {
        return try {
            runShellCommandToList("losetup -j \"${RootHelper.getCommandLineString(imagePath)}\"")
                .mapNotNull { line ->
                    line.substringBefore(":").takeIf { it.contains("loop") }?.trim()
                }.firstOrNull()
        } catch (e: Exception) {
            LOG.error("Unable to find loop device for {}", imagePath, e)
            null
        }
    }

    private fun findMountPoint(loopDevice: String): String? {
        return try {
            runShellCommandToList("mount").firstNotNullOfOrNull { line ->
                val tokens = line.split(" ")
                if (tokens.isEmpty()) {
                    return@firstNotNullOfOrNull null
                }
                val deviceToken = tokens[0]
                if (deviceToken != loopDevice) {
                    return@firstNotNullOfOrNull null
                }
                if (tokens.size > 2 && tokens[1] == "on") {
                    tokens[2]
                } else if (tokens.size > 1) {
                    tokens[1]
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            LOG.error("Unable to resolve mount point for {}", loopDevice, e)
            null
        }
    }

    private fun detachLoopDevice(imagePath: String) {
        try {
            val loopDevice = findLoopDevice(imagePath) ?: return
            val detachResult = runShellCommand("losetup -d \"$loopDevice\"")
            if (detachResult.code != 0) {
                LOG.error(
                    "Failed to detach loop device {} for {} (code {})",
                    loopDevice,
                    imagePath,
                    detachResult.code,
                )
            }
        } catch (e: Exception) {
            LOG.error("Unable to detach loop device for {}", imagePath, e)
        }
    }
}

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

import com.amaze.filemanager.fileoperations.exceptions.ShellNotRunningException
import com.amaze.filemanager.filesystem.RootHelper
import com.amaze.filemanager.filesystem.root.base.IRootCommand
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object MountImageCommand : IRootCommand() {
    private val log = LoggerFactory.getLogger(MountImageCommand::class.java)

    data class MountedImage(val loopDevice: String, val mountPoint: String)

    data class MountResult(
        val success: Boolean,
        val mountPoint: String? = null,
        val errorMessage: String? = null,
    )

    private val mountedImages: MutableMap<String, MountedImage> = ConcurrentHashMap()

    fun supportsLoopDevices(): Boolean {
        return File("/dev/loop-control").exists() ||
            File("/dev/block/loop0").exists() ||
            File("/dev/loop0").exists()
    }

    fun getMountedImage(imagePath: String): MountedImage? {
        return mountedImages[imagePath]
    }

    fun resolveMountedImage(imagePath: String): MountedImage? {
        mountedImages[imagePath]?.let { return it }
        return findMountedImage(imagePath)
    }

    @Throws(ShellNotRunningException::class)
    fun mountImage(
        imagePathArg: String,
        mountPointArg: String,
    ): MountResult {
        val imagePath = RootHelper.getCommandLineString(imagePathArg)
        val mountPoint = RootHelper.getCommandLineString(mountPointArg)
        if (imagePath.isBlank() || mountPoint.isBlank()) {
            return MountResult(false, errorMessage = "Invalid mount parameters")
        }

        return try {
            val loopDevice =
                attachLoopDevice(imagePath) ?: return MountResult(
                    success = false,
                    errorMessage = "Unable to allocate loop device",
                )

            val directoryResult = runShellCommand("mkdir -p \"$mountPoint\"")
            if (directoryResult.code != 0) {
                detachLoopDevice(loopDevice)
                return MountResult(
                    success = false,
                    errorMessage = directoryResult.err.joinToString("\n")
                        .ifEmpty { directoryResult.out.joinToString("\n") },
                )
            }

            val mountResult = runShellCommand("mount -o loop \"$loopDevice\" \"$mountPoint\"")
            if (mountResult.code == 0) {
                val mountedImage = MountedImage(loopDevice, mountPoint)
                mountedImages[imagePathArg] = mountedImage
                MountResult(true, mountPoint = mountPoint)
            } else {
                detachLoopDevice(loopDevice)
                MountResult(
                    success = false,
                    errorMessage = mountResult.err.joinToString("\n")
                        .ifEmpty { mountResult.out.joinToString("\n") },
                )
            }
        } catch (e: Exception) {
            log.error("Failed to mount image {}", imagePathArg, e)
            MountResult(false, errorMessage = e.message)
        }
    }

    @Throws(ShellNotRunningException::class)
    fun unmountImage(imagePathArg: String): MountResult {
        val mountedImage =
            mountedImages[imagePathArg] ?: findMountedImage(imagePathArg)
                ?: return MountResult(false, errorMessage = "Image not mounted")

        return try {
            val unmountResult = runShellCommand("umount \"${mountedImage.mountPoint}\"")
            if (unmountResult.code != 0) {
                val error =
                    unmountResult.err.joinToString("\n")
                        .ifEmpty { unmountResult.out.joinToString("\n") }
                log.warn("Failed to unmount {}: {}", mountedImage.mountPoint, error)
                return MountResult(false, mountPoint = mountedImage.mountPoint, errorMessage = error)
            }

            val detachResult = detachLoopDevice(mountedImage.loopDevice)
            if (!detachResult) {
                return MountResult(
                    false,
                    mountPoint = mountedImage.mountPoint,
                    errorMessage = "Failed to detach ${mountedImage.loopDevice}",
                )
            }
            mountedImages.remove(imagePathArg)
            MountResult(true, mountPoint = mountedImage.mountPoint)
        } catch (e: Exception) {
            log.error("Failed to unmount image {}", imagePathArg, e)
            MountResult(false, mountPoint = mountedImage.mountPoint, errorMessage = e.message)
        }
    }

    private fun attachLoopDevice(imagePath: String): String? {
        return try {
            val output = runShellCommandToList("losetup -f --show \"$imagePath\"")
            output.firstOrNull()?.trim()
        } catch (e: Exception) {
            log.error("Failed to attach loop device for {}", imagePath, e)
            null
        }
    }

    private fun detachLoopDevice(loopDevice: String): Boolean {
        return try {
            val result = runShellCommand("losetup -d \"$loopDevice\"")
            if (result.code != 0) {
                log.warn("Unable to detach {}: {}", loopDevice, result.err.joinToString("\n"))
            }
            result.code == 0
        } catch (e: Exception) {
            log.error("Failed to detach loop device {}", loopDevice, e)
            false
        }
    }

    private fun findMountedImage(imagePathArg: String): MountedImage? {
        if (!supportsLoopDevices()) return null
        val sanitizedPath = RootHelper.getCommandLineString(imagePathArg)
        return try {
            val loopInfoResult = runShellCommand("losetup -j \"$sanitizedPath\"")
            val loopLine = loopInfoResult.out.firstOrNull() ?: return null
            val loopDevice = loopLine.substringBefore(":").trim()
            val mountResult = runShellCommand("mount | grep \"$loopDevice\"")
            val mountPoint = mountResult.out.firstOrNull()?.let { parseMountPoint(it) }
            if (!mountPoint.isNullOrEmpty()) {
                val mountedImage = MountedImage(loopDevice, mountPoint)
                mountedImages[imagePathArg] = mountedImage
                mountedImage
            } else {
                null
            }
        } catch (e: Exception) {
            log.warn("Unable to resolve mount info for {}", imagePathArg, e)
            null
        }
    }

    private fun parseMountPoint(line: String): String? {
        val tokens = line.split(" ")
        return when {
            tokens.size >= 3 && tokens[1] == "on" -> tokens[2]
            tokens.size >= 2 -> tokens[1]
            else -> null
        }
    }
}

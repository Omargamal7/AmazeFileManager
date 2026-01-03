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

package com.amaze.filemanager.filesystem.compressed.extractcontents.helpers

import android.content.Context
import com.amaze.filemanager.R
import com.amaze.filemanager.application.AppConfig
import com.amaze.filemanager.fileoperations.utils.UpdatePosition
import com.amaze.filemanager.filesystem.FileUtil
import com.amaze.filemanager.filesystem.MakeDirectoryOperation
import com.amaze.filemanager.filesystem.compressed.extractcontents.Extractor
import com.amaze.filemanager.filesystem.files.GenericCopyUtil
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream

class IsoImageExtractor(
    context: Context,
    filePath: String,
    outputPath: String,
    listener: OnUpdate,
    updatePosition: UpdatePosition,
) :
    Extractor(context, filePath, outputPath, listener, updatePosition) {
    override fun extractWithFilter(filter: Filter) {
        val source = File(filePath)
        val entryName = source.nameWithoutExtension
        if (!filter.shouldExtract(entryName, false)) {
            throw EmptyArchiveNotice()
        }
        listener.onStart(source.length(), entryName)
        val outputFile = File(outputPath, entryName)
        if (false == outputFile.parentFile?.exists()) {
            MakeDirectoryOperation.mkdir(outputFile.parentFile, context)
        }
        FileUtil.getOutputStream(outputFile, context)?.let { outputStream ->
            BufferedOutputStream(outputStream).use { bufferedOutputStream ->
                FileInputStream(source).use { inputStream ->
                    val buffer = ByteArray(GenericCopyUtil.DEFAULT_BUFFER_SIZE)
                    var len: Int
                    while (inputStream.read(buffer).also { len = it } != -1) {
                        bufferedOutputStream.write(buffer, 0, len)
                        updatePosition.updatePosition(len.toLong())
                    }
                }
            }
            outputFile.setLastModified(source.lastModified())
        } ?: AppConfig.toast(
            context,
            context.getString(
                R.string.error_archive_cannot_extract,
                entryName,
                outputPath,
            ),
        )
        listener.onFinish()
    }
}

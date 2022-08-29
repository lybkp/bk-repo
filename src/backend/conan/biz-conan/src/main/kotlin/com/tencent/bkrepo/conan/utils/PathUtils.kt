/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2022 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.bkrepo.conan.utils

import com.tencent.bkrepo.common.api.constant.CharPool
import com.tencent.bkrepo.conan.constant.EXPORT_FOLDER
import com.tencent.bkrepo.conan.constant.PACKAGES_FOLDER
import com.tencent.bkrepo.conan.pojo.ConanFileReference
import com.tencent.bkrepo.conan.pojo.PackageReference
import org.apache.commons.lang3.StringUtils

object PathUtils {

    fun String.extractConanFileReference() : ConanFileReference {
        val pathList = this.trim('/').split("/")
        if (pathList.size < 7) throw IllegalArgumentException("invalid path $this")
        val userName = pathList[0]
        val name = pathList[1]
        val version = pathList[2]
        val channel = pathList[3]
        val revision = pathList[4]
        return ConanFileReference(name, version, userName, channel,revision)
    }

    fun joinString(first: String, second: String): String {
        return StringBuilder(first)
            .append(CharPool.SLASH)
            .append(second)
            .toString()
    }

    fun buildConanFileName(fileReference: ConanFileReference): String {
        with(fileReference) {
            return StringBuilder(name)
                .append(CharPool.SLASH)
                .append(version)
                .append(CharPool.AT)
                .append(userName)
                .append(CharPool.SLASH)
                .append(channel)
                .toString()
        }
    }

    fun buildPackagePath(fileReference: ConanFileReference): String {
        with(fileReference) {
            return StringBuilder(userName)
                .append(CharPool.SLASH)
                .append(name)
                .append(CharPool.SLASH)
                .append(version)
                .append(CharPool.SLASH)
                .append(channel)
                .toString()
        }
    }

    fun buildReference(fileReference: ConanFileReference) : String {
        with(fileReference) {
            return StringBuilder(userName)
                .append(CharPool.SLASH)
                .append(version)
                .append(CharPool.AT)
                .append(name)
                .append(CharPool.SLASH)
                .append(channel)
                .toString()
        }
    }

    fun buildPackageReference(packageReference: PackageReference): String {
        with(packageReference) {
            return StringBuilder(buildPackagePath(conRef))
                .append(CharPool.HASH_TAG)
                .append(conRef.revision)
                .append(CharPool.COLON)
                .append(packageId)
                .toString()
        }
    }

    fun buildRevisionPath(fileReference: ConanFileReference): String {
        with(fileReference) {
            return StringBuilder(buildPackagePath(fileReference))
                .append(CharPool.SLASH)
                .append(revision)
                .toString()
        }
    }

    fun buildExportFolderPath(fileReference: ConanFileReference): String {
        with(fileReference) {
            return StringBuilder(buildRevisionPath(fileReference))
                .append(CharPool.SLASH)
                .append(EXPORT_FOLDER)
                .toString()
        }
    }

    fun buildPackageFolderPath(packageReference: PackageReference): String {
        with(packageReference) {
            return StringBuilder(buildRevisionPath(conRef))
                .append(CharPool.SLASH)
                .append(PACKAGES_FOLDER)
                .append(CharPool.SLASH)
                .append(packageId)
                .append(CharPool.SLASH)
                .append(revision)
                .toString()
        }
    }
}

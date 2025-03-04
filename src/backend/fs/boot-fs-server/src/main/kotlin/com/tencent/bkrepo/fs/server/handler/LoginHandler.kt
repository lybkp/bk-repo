/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company.  All rights reserved.
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

package com.tencent.bkrepo.fs.server.handler

import com.tencent.bkrepo.auth.pojo.enums.PermissionAction
import com.tencent.bkrepo.common.api.constant.HttpHeaders
import com.tencent.bkrepo.common.artifact.constant.PROJECT_ID
import com.tencent.bkrepo.common.artifact.constant.REPO_NAME
import com.tencent.bkrepo.common.security.constant.BASIC_AUTH_PREFIX
import com.tencent.bkrepo.common.security.exception.AuthenticationException
import com.tencent.bkrepo.common.security.util.BasicAuthUtils
import com.tencent.bkrepo.fs.server.constant.JWT_CLAIMS_PERMIT
import com.tencent.bkrepo.fs.server.constant.JWT_CLAIMS_REPOSITORY
import com.tencent.bkrepo.fs.server.api.RAuthClient
import com.tencent.bkrepo.fs.server.service.PermissionService
import com.tencent.bkrepo.fs.server.utils.ReactiveResponseBuilder
import com.tencent.bkrepo.fs.server.utils.SecurityManager
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse

/**
 * 登录处理器
 * */
class LoginHandler(
    private val permissionService: PermissionService,
    private val securityManager: SecurityManager,
    private val rAuthClient: RAuthClient
) {

    /**
     * 登录请求
     * */
    suspend fun login(request: ServerRequest): ServerResponse {
        val projectId = request.pathVariable(PROJECT_ID)
        val repoName = request.pathVariable(REPO_NAME)

        val authorizationHeader = request.headers().header(HttpHeaders.AUTHORIZATION).firstOrNull().orEmpty()

        if (!authorizationHeader.startsWith(BASIC_AUTH_PREFIX)) {
            throw AuthenticationException()
        }
        val (username, password) = BasicAuthUtils.decode(authorizationHeader)
        val tokenRes = rAuthClient.checkToken(username, password).awaitSingle()
        if (tokenRes.data != true) {
            throw AuthenticationException()
        }

        val claims = mutableMapOf(JWT_CLAIMS_REPOSITORY to "$projectId/$repoName")
        val writePermit = permissionService.checkPermission(projectId, repoName, PermissionAction.WRITE, username)
        if (writePermit) {
            claims[JWT_CLAIMS_PERMIT] = PermissionAction.WRITE.name
        } else {
            val readPermit = permissionService.checkPermission(projectId, repoName, PermissionAction.READ, username)
            if (readPermit) {
                claims[JWT_CLAIMS_PERMIT] = PermissionAction.READ.name
            }
        }
        val token = securityManager.generateToken(
            subject = username,
            claims = claims
        )
        return ReactiveResponseBuilder.success(token)
    }
}

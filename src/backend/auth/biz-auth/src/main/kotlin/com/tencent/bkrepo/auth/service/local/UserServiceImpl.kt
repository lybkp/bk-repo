/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2020 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.tencent.bkrepo.auth.service.local

import com.mongodb.BasicDBObject
import com.tencent.bkrepo.auth.constant.DEFAULT_PASSWORD
import com.tencent.bkrepo.auth.message.AuthMessageCode
import com.tencent.bkrepo.auth.model.TUser
import com.tencent.bkrepo.auth.pojo.token.Token
import com.tencent.bkrepo.auth.pojo.token.TokenResult
import com.tencent.bkrepo.auth.pojo.user.CreateUserRequest
import com.tencent.bkrepo.auth.pojo.user.CreateUserToProjectRequest
import com.tencent.bkrepo.auth.pojo.user.CreateUserToRepoRequest
import com.tencent.bkrepo.auth.pojo.user.UpdateUserRequest
import com.tencent.bkrepo.auth.pojo.user.User
import com.tencent.bkrepo.auth.pojo.user.UserInfo
import com.tencent.bkrepo.auth.repository.RoleRepository
import com.tencent.bkrepo.auth.repository.UserRepository
import com.tencent.bkrepo.auth.service.UserService
import com.tencent.bkrepo.auth.util.DataDigestUtils
import com.tencent.bkrepo.auth.util.IDUtil
import com.tencent.bkrepo.common.api.constant.ANONYMOUS_USER
import com.tencent.bkrepo.common.api.exception.ErrorCodeException
import com.tencent.bkrepo.common.api.message.CommonMessageCode
import com.tencent.bkrepo.common.api.pojo.Page
import com.tencent.bkrepo.common.api.sensitive.DesensitizedUtils
import com.tencent.bkrepo.common.mongo.dao.util.Pages
import com.tencent.bkrepo.repository.api.ProjectClient
import com.tencent.bkrepo.repository.api.RepositoryClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

class UserServiceImpl constructor(
    private val userRepository: UserRepository,
    roleRepository: RoleRepository,
    private val mongoTemplate: MongoTemplate
) : UserService, AbstractServiceImpl(mongoTemplate, userRepository, roleRepository) {

    @Autowired
    lateinit var repositoryClient: RepositoryClient

    @Autowired
    lateinit var projectClient: ProjectClient

    override fun createUser(request: CreateUserRequest): Boolean {
        // todo 校验
        logger.info("create user request : [${DesensitizedUtils.toString(request)}]")
        // create a anonymous user is not allowed
        if (request.userId == ANONYMOUS_USER) {
            logger.warn("create user [${request.userId}]  is exist.")
            throw ErrorCodeException(AuthMessageCode.AUTH_DUP_UID)
        }
        val user = userRepository.findFirstByUserId(request.userId)
        user?.let {
            logger.warn("create user [${request.userId}]  is exist.")
            throw ErrorCodeException(AuthMessageCode.AUTH_DUP_UID)
        }
        if (request.group && request.asstUsers.isEmpty()) {
            throw ErrorCodeException(AuthMessageCode.AUTH_ASST_USER_EMPTY)
        }
        var pwd: String = DataDigestUtils.md5FromStr(DEFAULT_PASSWORD)
        request.pwd?.let {
            pwd = DataDigestUtils.md5FromStr(request.pwd!!)
        }
        userRepository.insert(
            TUser(
                userId = request.userId,
                name = request.name,
                pwd = pwd,
                admin = request.admin,
                locked = false,
                tokens = emptyList(),
                roles = emptyList(),
                asstUsers = request.asstUsers,
                group = request.group,
                email = request.email,
                phone = request.phone,
                createdDate = LocalDateTime.now(),
                lastModifiedDate = LocalDateTime.now()
            )
        )
        return true
    }

    override fun createUserToRepo(request: CreateUserToRepoRequest): Boolean {
        logger.info("create user to repo request : [${DesensitizedUtils.toString(request)}]")
        repositoryClient.getRepoInfo(request.projectId, request.repoName).data ?: run {
            logger.warn("repo [${request.projectId}/${request.repoName}]  not exist.")
            throw ErrorCodeException(AuthMessageCode.AUTH_REPO_NOT_EXIST)
        }
        // user not exist, create user
        try {
            val userResult = createUser(convCreateRepoUserRequest(request))
            if (!userResult) {
                logger.warn("create user fail [$userResult]")
                return false
            }
        } catch (exception: ErrorCodeException) {
            if (exception.messageCode == AuthMessageCode.AUTH_DUP_UID) {
                return true
            }
            throw exception
        }
        return true
    }

    override fun createUserToProject(request: CreateUserToProjectRequest): Boolean {
        logger.info("create user to project request : [${DesensitizedUtils.toString(request)}]")
        projectClient.getProjectInfo(request.projectId).data ?: run {
            logger.warn("project [${request.projectId}]  not exist.")
            throw ErrorCodeException(AuthMessageCode.AUTH_PROJECT_NOT_EXIST)
        }
        // user not exist, create user
        try {
            val userResult = createUser(convCreateProjectUserRequest(request))
            if (!userResult) {
                logger.warn("create user fail [$userResult]")
                return false
            }
        } catch (exception: ErrorCodeException) {
            if (exception.messageCode == AuthMessageCode.AUTH_DUP_UID) {
                return true
            }
            throw exception
        }
        return true
    }

    override fun listUser(rids: List<String>): List<User> {
        logger.debug("list user rids : [$rids]")
        return if (rids.isEmpty()) {
            // 排除被锁定的用户
            val query = Query(Criteria(TUser::locked.name).`is`(false))
            mongoTemplate.find(query, TUser::class.java).map { transferUser(it) }
        } else {
            userRepository.findAllByRolesIn(rids).map { transferUser(it) }
        }
    }

    override fun deleteById(userId: String): Boolean {
        logger.info("delete user userId : [$userId]")
        checkUserExist(userId)
        userRepository.deleteByUserId(userId)
        return true
    }

    override fun addUserToRole(userId: String, roleId: String): User? {
        logger.info("add user to role userId : [$userId], roleId : [$roleId]")
        // check user
        checkUserExist(userId)
        // check role
        checkRoleExist(roleId)
        // check is role bind to role
        val query = Query()
        val update = Update()
        if (!checkUserRoleBind(userId, roleId)) {
            query.addCriteria(Criteria.where(TUser::userId.name).`is`(userId))
            update.addToSet(TUser::roles.name, roleId)
            mongoTemplate.upsert(query, update, TUser::class.java)
        }
        return getUserById(userId)
    }

    override fun addUserToRoleBatch(idList: List<String>, roleId: String): Boolean {
        logger.info("add user to role batch userId : [$idList], roleId : [$roleId]")
        checkUserExistBatch(idList)
        checkRoleExist(roleId)
        val query = Query()
        val update = Update()
        query.addCriteria(Criteria.where(TUser::userId.name).`in`(idList))
        update.addToSet(TUser::roles.name, roleId)
        mongoTemplate.updateMulti(query, update, TUser::class.java)
        return true
    }

    override fun removeUserFromRole(userId: String, roleId: String): User? {
        logger.info("remove user from role userId : [$userId], roleId : [$roleId]")
        // check user
        checkUserExist(userId)
        // check role
        checkRoleExist(roleId)
        val query = Query()
        val update = Update()
        query.addCriteria(Criteria.where(TUser::userId.name).`is`(userId).and(TUser::roles.name).`is`(roleId))
        update.unset("roles.$")
        mongoTemplate.upsert(query, update, TUser::class.java)
        return getUserById(userId)
    }

    override fun removeUserFromRoleBatch(idList: List<String>, roleId: String): Boolean {
        logger.info("remove user from role  batch userId : [$idList], roleId : [$roleId]")
        checkUserExistBatch(idList)
        checkRoleExist(roleId)
        val query = Query()
        val update = Update()
        query.addCriteria(Criteria.where(TUser::userId.name).`in`(idList).and(TUser::roles.name).`is`(roleId))
        update.unset("roles.$")
        val result = mongoTemplate.updateMulti(query, update, TUser::class.java)
        if (result.modifiedCount == 1L) return true
        return false
    }

    override fun updateUserById(userId: String, request: UpdateUserRequest): Boolean {
        logger.info("update user userId : [$userId], request : [$request]")
        checkUserExist(userId)

        val query = Query()
        query.addCriteria(Criteria.where(TUser::userId.name).`is`(userId))
        val update = Update()
        request.pwd?.let {
            val pwd = DataDigestUtils.md5FromStr(request.pwd!!)
            update.set(TUser::pwd.name, pwd)
        }
        request.admin?.let {
            update.set(TUser::admin.name, request.admin)
        }
        request.name?.let {
            update.set(TUser::name.name, request.name)
        }
        request.email?.let {
            update.set(TUser::email.name, request.email)
        }
        request.locked?.let {
            update.set(TUser::locked.name, request.locked)
        }
        request.phone?.let {
            update.set(TUser::phone.name, request.phone)
        }
        update.set(TUser::lastModifiedDate.name, LocalDateTime.now())
        val result = mongoTemplate.updateFirst(query, update, TUser::class.java)
        if (result.modifiedCount == 1L) return true
        return false
    }

    override fun createToken(userId: String): Token? {
        logger.info("create token userId : [$userId]")
        val token = IDUtil.genRandomId()
        return addUserToken(userId, token, null)
    }

    override fun addUserToken(userId: String, name: String, expiredAt: String?): Token? {
        try {
            logger.info("add user token userId : [$userId] ,token : [$name]")
            checkUserExist(userId)

            val existUserInfo = getUserById(userId)
            val existTokens = existUserInfo!!.tokens
            var id = IDUtil.genRandomId()
            var createdTime = LocalDateTime.now()
            existTokens.forEach {
                // 如果临时token已经存在，尝试更新token的过期时间
                if (it.name == name && it.expiredAt != null) {
                    // 先删除token
                    removeToken(userId, name)
                    id = it.id
                    createdTime = it.createdAt
                } else if (it.name == name && it.expiredAt == null) {
                    logger.warn("user token exist [$name]")
                    throw ErrorCodeException(AuthMessageCode.AUTH_USER_TOKEN_EXIST)
                }
            }
            // 创建token
            val query = Query.query(Criteria.where(TUser::userId.name).`is`(userId))
            val update = Update()
            var expiredTime: LocalDateTime? = null
            if (expiredAt != null && expiredAt.isNotEmpty()) {
                val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                expiredTime = LocalDateTime.parse(expiredAt, dateTimeFormatter)
                // conv time
                expiredTime = expiredTime!!.plusHours(8)
            }
            val userToken = Token(
                name = name,
                id = id,
                createdAt = createdTime,
                expiredAt = expiredTime
            )
            update.addToSet(TUser::tokens.name, userToken)
            mongoTemplate.upsert(query, update, TUser::class.java)
            val userInfo = getUserById(userId)
            val tokens = userInfo!!.tokens
            tokens.forEach {
                if (it.name == name) {
                    return it
                }
            }
            return null
        } catch (ignored: DateTimeParseException) {
            logger.error("add user token false [$ignored]")
            throw ErrorCodeException(AuthMessageCode.AUTH_USER_TOKEN_ERROR)
        }
    }

    override fun listUserToken(userId: String): List<TokenResult> {
        checkUserExist(userId)
        val userInfo = getUserById(userId)
        val tokens = userInfo!!.tokens
        val result = mutableListOf<TokenResult>()
        tokens.forEach {
            result.add(
                TokenResult(
                    it.name,
                    it.createdAt,
                    it.expiredAt
                )
            )
        }
        return result
    }

    override fun removeToken(userId: String, name: String): Boolean {
        logger.info("remove token userId : [$userId] ,name : [$name]")
        checkUserExist(userId)
        val query = Query.query(Criteria.where(TUser::userId.name).`is`(userId))
        val s = BasicDBObject()
        s["name"] = name
        val update = Update()
        update.pull(TUser::tokens.name, s)
        mongoTemplate.updateFirst(query, update, TUser::class.java)
        return true
    }

    override fun getUserById(userId: String): User? {
        logger.debug("get user userId : [$userId]")
        val user = userRepository.findFirstByUserId(userId) ?: return null
        return transferUser(user)
    }

    override fun findUserByUserToken(userId: String, pwd: String): User? {
        logger.debug("find user userId : [$userId]")
        val hashPwd = DataDigestUtils.md5FromStr(pwd)
        val criteria = Criteria()
        criteria.orOperator(
            Criteria.where(TUser::pwd.name).`is`(hashPwd),
            Criteria.where("tokens.id").`is`(pwd)
        ).and(TUser::userId.name).`is`(userId)
        val query = Query.query(criteria)
        val result = mongoTemplate.findOne(query, TUser::class.java) ?: return null
        return transferUser(result)
    }

    override fun userPage(
        pageNumber: Int,
        pageSize: Int,
        user: String?,
        admin: Boolean?,
        locked: Boolean?
    ): Page<UserInfo> {
        val criteria = Criteria()
        user?.let {
            criteria.orOperator(
                Criteria.where(TUser::userId.name).regex("^$user"),
                Criteria.where(TUser::name.name).regex("^$user")
            )
        }
        admin?.let { criteria.and(TUser::admin.name).`is`(admin) }
        locked?.let { criteria.and(TUser::locked.name).`is`(locked) }
        val query = Query.query(criteria)
        val pageRequest = Pages.ofRequest(pageNumber, pageSize)
        val totalRecords = mongoTemplate.count(query, TUser::class.java)
        val records = mongoTemplate.find(query.with(pageRequest), TUser::class.java).map { transferUserInfo(it) }
        return Pages.ofResponse(pageRequest, totalRecords, records)
    }

    override fun getUserInfoById(uid: String): UserInfo? {
        val tUser = userRepository.findFirstByUserId(uid) ?: return null
        return transferUserInfo(tUser)
    }

    override fun updatePassword(uid: String, oldPwd: String, newPwd: String): Boolean {
        val query = Query.query(
            Criteria().andOperator(
                Criteria.where(TUser::userId.name).`is`(uid),
                Criteria.where(TUser::pwd.name).`is`(DataDigestUtils.md5FromStr(oldPwd))
            )
        )
        val user = mongoTemplate.find(query, TUser::class.java)
        if (user.isNotEmpty()) {
            val updateQuery = Query(Criteria(TUser::userId.name).`is`(uid))
            val update = Update().set(TUser::pwd.name, DataDigestUtils.md5FromStr(newPwd))
            val record = mongoTemplate.updateFirst(updateQuery, update, TUser::class.java)
            if (record.modifiedCount == 1L || record.matchedCount == 1L) return true
        }
        throw ErrorCodeException(CommonMessageCode.MODIFY_PASSWORD_FAILED, "modify password failed!")
    }

    override fun resetPassword(uid: String): Boolean {
        // todo 鉴权
        val updateQuery = Query(Criteria(TUser::userId.name).`is`(uid))
        val update = Update().set(TUser::pwd.name, DataDigestUtils.md5FromStr(DEFAULT_PASSWORD))
        val record = mongoTemplate.updateFirst(updateQuery, update, TUser::class.java)
        if (record.modifiedCount == 1L || record.matchedCount == 1L) return true
        return false
    }

    override fun repeatUid(uid: String): Boolean {
        val query = Query(Criteria(TUser::userId.name).`is`(uid))
        val record = mongoTemplate.find(query, TUser::class.java)
        return record.isNotEmpty()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(UserServiceImpl::class.java)
    }
}

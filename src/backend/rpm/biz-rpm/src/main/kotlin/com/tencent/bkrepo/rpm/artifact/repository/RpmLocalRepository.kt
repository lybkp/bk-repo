package com.tencent.bkrepo.rpm.artifact.repository

import com.tencent.bkrepo.common.api.constant.StringPool.DASH
import com.tencent.bkrepo.common.api.constant.StringPool.DOT
import com.tencent.bkrepo.common.api.constant.StringPool.SLASH
import com.tencent.bkrepo.common.api.util.toJsonString
import com.tencent.bkrepo.common.artifact.api.ArtifactFile
import com.tencent.bkrepo.common.artifact.constant.ATTRIBUTE_OCTET_STREAM_SHA256
import com.tencent.bkrepo.common.artifact.exception.ArtifactNotFoundException
import com.tencent.bkrepo.common.artifact.exception.UnsupportedMethodException
import com.tencent.bkrepo.common.artifact.hash.sha1
import com.tencent.bkrepo.common.artifact.pojo.configuration.local.repository.RpmLocalConfiguration
import com.tencent.bkrepo.common.artifact.repository.context.ArtifactRemoveContext
import com.tencent.bkrepo.common.artifact.repository.context.ArtifactTransferContext
import com.tencent.bkrepo.common.artifact.repository.context.ArtifactUploadContext
import com.tencent.bkrepo.common.artifact.repository.local.LocalRepository
import com.tencent.bkrepo.common.artifact.resolve.file.ArtifactFileFactory
import com.tencent.bkrepo.common.artifact.stream.Range
import com.tencent.bkrepo.common.query.model.PageLimit
import com.tencent.bkrepo.common.query.model.QueryModel
import com.tencent.bkrepo.common.query.model.Rule
import com.tencent.bkrepo.common.query.model.Sort
import com.tencent.bkrepo.common.service.util.HttpContextHolder
import com.tencent.bkrepo.repository.pojo.node.service.NodeCreateRequest
import com.tencent.bkrepo.repository.pojo.node.service.NodeDeleteRequest
import com.tencent.bkrepo.repository.pojo.repo.RepositoryInfo
import com.tencent.bkrepo.rpm.REPODATA
import com.tencent.bkrepo.rpm.OTHERS
import com.tencent.bkrepo.rpm.PRIMARY
import com.tencent.bkrepo.rpm.XMLGZ
import com.tencent.bkrepo.rpm.FILELISTS
import com.tencent.bkrepo.rpm.INDEXER
import com.tencent.bkrepo.rpm.NO_INDEXER
import com.tencent.bkrepo.rpm.GZ
import com.tencent.bkrepo.rpm.artifact.SurplusNodeCleaner
import com.tencent.bkrepo.rpm.exception.RpmArtifactFormatNotSupportedException
import com.tencent.bkrepo.rpm.exception.RpmArtifactMetadataResolveException
import com.tencent.bkrepo.rpm.pojo.RpmRepoConf
import com.tencent.bkrepo.rpm.pojo.ArtifactRepeat
import com.tencent.bkrepo.rpm.pojo.RpmVersion
import com.tencent.bkrepo.rpm.pojo.RpmUploadResponse
import com.tencent.bkrepo.rpm.pojo.RpmDeleteResponse
import com.tencent.bkrepo.rpm.pojo.ArtifactFormat
import com.tencent.bkrepo.rpm.pojo.ArtifactRepeat.FULLPATH_SHA256
import com.tencent.bkrepo.rpm.pojo.ArtifactRepeat.NONE
import com.tencent.bkrepo.rpm.pojo.ArtifactRepeat.FULLPATH
import com.tencent.bkrepo.rpm.util.GZipUtils.unGzipInputStream
import com.tencent.bkrepo.rpm.util.GZipUtils.gZip
import com.tencent.bkrepo.rpm.util.XmlStrUtils
import com.tencent.bkrepo.rpm.util.rpm.RpmMetadataUtils
import com.tencent.bkrepo.rpm.util.rpm.RpmFormatUtils
import com.tencent.bkrepo.rpm.util.xStream.XStreamUtil.objectToXml
import com.tencent.bkrepo.rpm.util.xStream.pojo.RpmXmlMetadata
import com.tencent.bkrepo.rpm.util.xStream.pojo.RpmMetadataChangeLog
import com.tencent.bkrepo.rpm.util.xStream.pojo.RpmMetadataFileList
import com.tencent.bkrepo.rpm.util.xStream.pojo.RpmPackageFileList
import com.tencent.bkrepo.rpm.util.xStream.pojo.RpmPackageChangeLog
import com.tencent.bkrepo.rpm.util.xStream.pojo.RpmLocation
import com.tencent.bkrepo.rpm.util.xStream.pojo.RpmChecksum
import com.tencent.bkrepo.rpm.util.xStream.repomd.RepoData
import com.tencent.bkrepo.rpm.util.xStream.repomd.Repomd
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.nio.channels.Channels
import com.tencent.bkrepo.rpm.pojo.ArtifactFormat.RPM
import com.tencent.bkrepo.rpm.pojo.ArtifactFormat.XML
import com.tencent.bkrepo.rpm.util.RpmCollectionUtils.filterRpmCustom
import com.tencent.bkrepo.rpm.util.RpmVersionUtils.toMetadata
import com.tencent.bkrepo.rpm.util.XmlStrUtils.getGroupNodeFullPath
import com.tencent.bkrepo.rpm.util.xStream.repomd.RepoGroup
import com.tencent.bkrepo.rpm.util.xStream.repomd.RepoIndex

@Component
class RpmLocalRepository(
    val surplusNodeCleaner: SurplusNodeCleaner
) : LocalRepository() {

    fun rpmNodeCreateRequest(
        context: ArtifactUploadContext,
        metadata: MutableMap<String, String>?,
        overwrite: Boolean
    ):
        NodeCreateRequest {
            val nodeCreateRequest = super.getNodeCreateRequest(context)
            return nodeCreateRequest.copy(
                overwrite = overwrite,
                metadata = metadata
            )
        }

    fun xmlIndexNodeCreate(
        userId: String,
        repositoryInfo: RepositoryInfo,
        fullPath: String,
        xmlGZArtifact: ArtifactFile,
        metadata: MutableMap<String, String>
    ): NodeCreateRequest {
        val sha256 = xmlGZArtifact.getFileSha256()
        val md5 = xmlGZArtifact.getFileMd5()
        return NodeCreateRequest(
            projectId = repositoryInfo.projectId,
            repoName = repositoryInfo.name,
            folder = false,
            overwrite = true,
            fullPath = fullPath,
            size = xmlGZArtifact.getSize(),
            sha256 = sha256,
            md5 = md5,
            operator = userId,
            metadata = metadata
        )
    }

    fun xmlNodeCreate(
        userId: String,
        repositoryInfo: RepositoryInfo,
        fullPath: String,
        xmlGZArtifact: ArtifactFile
    ): NodeCreateRequest {
        val sha256 = xmlGZArtifact.getFileSha256()
        val md5 = xmlGZArtifact.getFileMd5()
        return NodeCreateRequest(
            projectId = repositoryInfo.projectId,
            repoName = repositoryInfo.name,
            folder = false,
            overwrite = true,
            fullPath = fullPath,
            size = xmlGZArtifact.getSize(),
            sha256 = sha256,
            md5 = md5,
            operator = userId
        )
    }
    /**
     * 查询仓库设置的repodata 深度
     */
    @Deprecated("getRpmRepoConf()")
    private fun searchRpmRepoDataDepth(context: ArtifactUploadContext): Int {
        (context.repositoryInfo.configuration as RpmLocalConfiguration).repodataDepth.let { return it }
    }

    /**
     * 查询rpm仓库属性
     */
    private fun getRpmRepoConf(context: ArtifactTransferContext): RpmRepoConf {
        val rpmConfiguration = context.repositoryInfo.configuration as RpmLocalConfiguration
        val repodataDepth = rpmConfiguration.repodataDepth
        val enabledFileLists = rpmConfiguration.enabledFileLists
        val groupXmlSet = rpmConfiguration.groupXmlSet
        return RpmRepoConf(repodataDepth, enabledFileLists, groupXmlSet)
    }

    /**
     * 检查请求uri地址的层级是否 > 仓库设置的repodata 深度
     * @return true 将会计算rpm包的索引
     * @return false 只提供文件服务器功能，返回提示信息
     */
    private fun checkRequestUri(context: ArtifactUploadContext, repodataDepth: Int): Boolean {
        val artifactUri = context.artifactInfo.artifactUri
            .removePrefix(SLASH).split(SLASH).size
        return artifactUri > repodataDepth
    }

    /**
     * 生成构件索引
     */
    private fun indexer(context: ArtifactUploadContext, repeat: ArtifactRepeat, rpmRepoConf: RpmRepoConf): RpmVersion {

        val repodataDepth = rpmRepoConf.repodataDepth
        val repodataUri = XmlStrUtils.splitUriByDepth(context.artifactInfo.artifactUri, repodataDepth)
        val repodataPath = repodataUri.repodataPath

        val artifactFile = context.getArtifactFile()
        val rpmFormat = RpmFormatUtils.getRpmFormat(Channels.newChannel(artifactFile.getInputStream()))

        val sha1Digest = artifactFile.getInputStream().sha1()
        val artifactRelativePath = repodataUri.artifactRelativePath
        val rpmMetadata = RpmMetadataUtils().interpret(
            rpmFormat,
            artifactFile.getSize(),
            sha1Digest,
            artifactRelativePath
        )
        if (rpmRepoConf.enabledFileLists) {
            val rpmMetadataFileList = RpmMetadataFileList(
                listOf(
                    RpmPackageFileList(
                        rpmMetadata.packages[0].checksum.checksum,
                        rpmMetadata.packages[0].name,
                        rpmMetadata.packages[0].version,
                        rpmMetadata.packages[0].format.files
                    )
                ),
                1L
            )
            updateIndexXml(context, rpmMetadataFileList, repeat, repodataPath, FILELISTS)
            // 更新filelists.xml
            rpmMetadata.packages[0].format.files.clear()
        }
        val rpmMetadataChangeLog = RpmMetadataChangeLog(
            listOf(
                RpmPackageChangeLog(
                    rpmMetadata.packages[0].checksum.checksum,
                    rpmMetadata.packages[0].name,
                    rpmMetadata.packages[0].version,
                    rpmMetadata.packages[0].format.changeLogs
                )
            ),
            1L
        )
        // 更新others.xml
        updateIndexXml(context, rpmMetadataChangeLog, repeat, repodataPath, OTHERS)
        rpmMetadata.packages[0].format.changeLogs.clear()
        // 更新primary.xml
        updateIndexXml(context, rpmMetadata, repeat, repodataPath, PRIMARY)
        flushRepoMdXML(context)
        return RpmVersion(
            rpmMetadata.packages[0].name,
            rpmMetadata.packages[0].arch,
            rpmMetadata.packages[0].version.epoch.toString(),
            rpmMetadata.packages[0].version.ver,
            rpmMetadata.packages[0].version.rel
        )
    }

    private fun updateIndexXml(
        context: ArtifactUploadContext,
        rpmXmlMetadata: RpmXmlMetadata,
        repeat: ArtifactRepeat,
        repodataPath: String,
        indexType: String
    ) {
        val target = "$DASH$indexType$DOT$XMLGZ"

        with(context.artifactInfo) {
            // repodata下'-**.xml.gz'最新节点。
            val nodeList = nodeClient.list(
                projectId, repoName,
                "$SLASH${repodataPath}$REPODATA",
                includeFolder = false, deep = false
            ).data
            val targetNodelist = nodeList?.filter {
                it.name.endsWith(target)
            }?.sortedByDescending {
                it.createdDate
            }

            val targetXmlString = if (!targetNodelist.isNullOrEmpty()) {
                val latestPrimaryNode = targetNodelist[0]
                val inputStream = storageService.load(
                    latestPrimaryNode.sha256!!,
                    Range.full(latestPrimaryNode.size),
                    context.storageCredentials
                ) ?: return
                // 更新primary.xml
                if (repeat == NONE) {
                    XmlStrUtils.insertPackage(indexType, inputStream.unGzipInputStream(), rpmXmlMetadata, artifactUri)
                } else {
                    XmlStrUtils.updatePackage(indexType, inputStream.unGzipInputStream(), rpmXmlMetadata, artifactUri)
                }
            } else {
                // first upload
                rpmXmlMetadata.objectToXml()
            }

            // 删除多余索引节点
            GlobalScope.launch {
                targetNodelist?.let { surplusNodeCleaner.deleteSurplusNode(it) }
            }.start()
            storeXmlNode(indexType, targetXmlString, repodataPath, context, target)
        }
    }

    private fun deleteIndexXml(
        context: ArtifactRemoveContext,
        rpmVersion: RpmVersion,
        repodataPath: String,
        indexType: String
    ) {
        val target = "$DASH$indexType$DOT$XMLGZ"
        with(context.artifactInfo) {
            // repodata下'-**.xml.gz'最新节点。
            val nodeList = nodeClient.list(
                projectId, repoName,
                "$SLASH${repodataPath}$REPODATA",
                includeFolder = false, deep = false
            ).data
            val location = artifactUri.removePrefix("$SLASH$repodataPath")
            val targetNodelist = nodeList?.filter {
                it.name.endsWith(target)
            }?.sortedByDescending {
                it.createdDate
            }
            val targetXmlString = if (!targetNodelist.isNullOrEmpty()) {
                val latestPrimaryNode = targetNodelist[0]
                val inputStream = storageService.load(
                    latestPrimaryNode.sha256!!,
                    Range.full(latestPrimaryNode.size),
                    context.storageCredentials
                ) ?: return
                XmlStrUtils.deletePackage(indexType, inputStream.unGzipInputStream(), rpmVersion, location)
            } else {
                deleteFailed(context, "未找到$indexType.xml.gz 索引文件")
                return
            }
            // 删除多余索引节点
            GlobalScope.launch {
                targetNodelist.let { surplusNodeCleaner.deleteSurplusNode(it) }
            }.start()
            storeXmlNode(indexType, targetXmlString, repodataPath, context, target)
        }
    }

    /**
     * 保存索引节点
     * @param xmlStr ".xml" 索引文件内容
     * @param repodataPath 契合本次请求的repodata_depth 目录路径
     */
    private fun storeXmlNode(
        indexType: String,
        xmlStr: String,
        repodataPath: String,
        context: ArtifactTransferContext,
        target: String
    ) {
        ByteArrayInputStream((xmlStr.toByteArray())).use { xmlInputStream ->
            // 保存节点同时保存节点信息到元数据方便repomd更新。
            val xmlFileSize = xmlStr.toByteArray().size

            val xmlGZFile = xmlStr.toByteArray().gZip(indexType)
            val xmlFileSha1 = xmlInputStream.sha1()
            try {
                val xmlGZFileSha1 = FileInputStream(xmlGZFile).sha1()

                // 先保存primary-xml.gz文件
                val xmlGZArtifact = ArtifactFileFactory.build(FileInputStream(xmlGZFile))
                val fullPath = "$SLASH${repodataPath}$REPODATA$SLASH$xmlGZFileSha1$target"
                val metadata = mutableMapOf(
                    "indexType" to indexType,
                    "checksum" to xmlGZFileSha1,
                    "size" to (xmlGZArtifact.getSize().toString()),
                    "timestamp" to System.currentTimeMillis().toString(),
                    "openChecksum" to xmlFileSha1,
                    "openSize" to (xmlFileSize.toString())
                )
                val xmlPrimaryNode = xmlIndexNodeCreate(
                    context.userId,
                    context.repositoryInfo,
                    fullPath,
                    xmlGZArtifact,
                    metadata
                )
                storageService.store(xmlPrimaryNode.sha256!!, xmlGZArtifact, context.storageCredentials)
                nodeClient.create(xmlPrimaryNode)

                // 更新repomd.xml
                // xml文件sha1
            } finally {
                xmlGZFile.delete()
            }
        }
    }

    /**
     * 检查上传的构件是否已在仓库中，判断条件：uri && sha256
     * 降低并发对索引文件的影响
     * @return ArtifactRepeat.FULLPATH_SHA256 存在完全相同构件，不操作索引
     * @return ArtifactRepeat.FULLPATH 请求路径相同，但内容不同，更新索引
     * @return ArtifactRepeat.NONE 无重复构件
     */
    private fun checkRepeatArtifact(context: ArtifactUploadContext): ArtifactRepeat {
        val artifactUri = context.artifactInfo.artifactUri
        val artifactSha256 = context.contextAttributes[ATTRIBUTE_OCTET_STREAM_SHA256] as String

        return with(context.artifactInfo) {
            val node = nodeClient.detail(projectId, repoName, artifactUri).data
            if (node == null) {
                NONE
            } else {
                if (node.sha256 == artifactSha256) {
                    FULLPATH_SHA256
                } else {
                    FULLPATH
                }
            }
        }
    }

    private fun successUpload(context: ArtifactUploadContext, mark: Boolean, repodataDepth: Int) {
        val response = HttpContextHolder.getResponse()
        response.contentType = "application/json; charset=UTF-8"
        with(context.artifactInfo) {
            val description = if (mark) {
                INDEXER
            } else {
                String.format(NO_INDEXER, "$projectId/$repoName", repodataDepth, artifactUri)
            }
            val rpmUploadResponse = RpmUploadResponse(
                projectId, repoName, artifactUri,
                context.getArtifactFile().getFileSha256(), context.getArtifactFile().getFileMd5(), description
            )
            response.writer.print(rpmUploadResponse.toJsonString())
        }
    }

    private fun deleteFailed(context: ArtifactRemoveContext, description: String) {
        val response = HttpContextHolder.getResponse()
        response.contentType = "application/json; charset=UTF-8"
        with(context.artifactInfo) {
            val rpmUploadResponse = RpmDeleteResponse(
                projectId, repoName, artifactUri,
                description
            )
            response.writer.print(rpmUploadResponse.toJsonString())
        }
    }

    private fun getArtifactFormat(context: ArtifactUploadContext): ArtifactFormat {
        val format = context.artifactInfo.artifactUri
            .split(SLASH).last().split(".").last()
        return when (format) {
            "xml" -> XML
            "rpm" -> RPM
            else -> throw RpmArtifactFormatNotSupportedException("rpm not supported `$format` artifact")
        }
    }

    // 保存分组文件
    private fun storeGroupFile(context: ArtifactUploadContext, repeat: ArtifactRepeat, rpmRepoConf: RpmRepoConf) {
        val xmlByteArray = context.getArtifactFile().getInputStream().readBytes()
        val filename = context.artifactInfo.artifactUri.split("/").last()

        // 保存xml
        val xmlSha1 = context.getArtifactFile().getInputStream().sha1()
        val xmlSha1ArtifactFile = ArtifactFileFactory.build(xmlByteArray.inputStream())
        val metadata = mutableMapOf(
            "indexName" to filename,
            "indexType" to "group",
            "checksum" to xmlSha1,
            "size" to (xmlSha1ArtifactFile.getSize().toString()),
            "timestamp" to System.currentTimeMillis().toString()
        )
        val xmlNode = xmlIndexNodeCreate(
            context.userId,
            context.repositoryInfo,
            getGroupNodeFullPath(context.artifactInfo.artifactUri, xmlSha1),
            xmlSha1ArtifactFile,
            metadata
        )
        storageService.store(xmlNode.sha256!!, xmlSha1ArtifactFile, context.storageCredentials)
        nodeClient.create(xmlNode)
        xmlSha1ArtifactFile.delete()

        // 保存xml.gz
        val groupGZFile = xmlByteArray.gZip("random")
        try {
            val xmlGZFileSha1 = FileInputStream(groupGZFile).sha1()
            val groupGZArtifactFile = ArtifactFileFactory.build(FileInputStream(groupGZFile))
            val metadataGZ = mutableMapOf(
                "indexName" to "${filename}_gz",
                "indexType" to "group_gz",
                "checksum" to xmlGZFileSha1,
                "size" to (groupGZArtifactFile.getSize().toString()),
                "timestamp" to System.currentTimeMillis().toString()
            )
            val groupGZNode = xmlIndexNodeCreate(
                context.userId,
                context.repositoryInfo,
                getGroupNodeFullPath("${context.artifactInfo.artifactUri}$DOT$GZ", xmlGZFileSha1),
                groupGZArtifactFile,
                metadataGZ
            )
            storageService.store(groupGZNode.sha256!!, groupGZArtifactFile, context.storageCredentials)
            nodeClient.create(groupGZNode)
            groupGZArtifactFile.delete()
        } finally {
            groupGZFile.delete()
        }

        // todo
        // 删除多余节点
        flushRepoMdXML(context)
    }

    // 刷新`repomd.xml`内容
    fun flushRepoMdXML(context: ArtifactTransferContext) {
        // 查询添加的groups
        val configuration = (context.repositoryConfiguration as RpmLocalConfiguration)
        val groupXmlSet = configuration.groupXmlSet
        val repodataDepth = configuration.repodataDepth
        val repodataUri = XmlStrUtils.splitUriByDepth(context.artifactInfo.artifactUri, repodataDepth)
        val indexPath = "${repodataUri.repodataPath}$REPODATA"

        // 查询该请求路径对应的索引目录下所有文件
        val nodeList = with(context.artifactInfo) {
            val projectQuery = Rule.QueryRule("projectId", projectId)
            val repositoryQuery = Rule.QueryRule("repoName", repoName)
            val pathQuery = Rule.QueryRule("path", "$SLASH$indexPath$SLASH")

            val queryRule = Rule.NestedRule(
                mutableListOf(projectQuery, repositoryQuery, pathQuery),
                Rule.NestedRule.RelationType.AND
            )
            // TODO limit大小
            val queryModel = QueryModel(
                page = PageLimit(0, 15),
                sort = Sort(listOf("lastModifiedDate"), Sort.Direction.DESC),
                select = mutableListOf("projectId", "repoName", "path", "name", "lastModifiedDate", "metadata"),
                rule = queryRule
            )
            nodeClient.query(queryModel).data?.records
        }

        val targetIndexList = nodeList?.filterRpmCustom(groupXmlSet, configuration.enabledFileLists)

        val repoDataList = mutableListOf<RepoIndex>()
        if (targetIndexList != null) {
            for (index in targetIndexList) {
                repoDataList.add(
                    if ((index["name"] as String).contains(Regex("-filelists|-others|-primary"))) {
                        RepoData(
                            type = (index["metadata"] as Map<*, *>)["indexType"] as String,
                            location = RpmLocation("$REPODATA$SLASH${index["name"] as String}"),
                            checksum = RpmChecksum((index["metadata"] as Map<*, *>)["checksum"] as String),
                            size = ((index["metadata"] as Map<*, *>)["size"] as String).toLong(),
                            timestamp = (index["metadata"] as Map<*, *>)["timestamp"] as String,
                            openChecksum = RpmChecksum((index["metadata"] as Map<*, *>)["openChecksum"] as String),
                            openSize = (index["metadata"] as Map<String, String>)["size"]?.toInt() ?: 111
                        )
                    } else {
                        RepoGroup(
                            type = (index["metadata"] as Map<*, *>)["indexType"] as String,
                            location = RpmLocation("$REPODATA$SLASH${index["name"] as String}"),
                            checksum = RpmChecksum((index["metadata"] as Map<*, *>)["checksum"] as String),
                            size = (index["metadata"] as Map<String, String>)["size"]?.toLong() ?: 111L,
                            timestamp = (index["metadata"] as Map<*, *>)["timestamp"] as String
                        )
                    }
                )
            }
        }
        val repomd = Repomd(
            repoDataList
        )
        val xmlRepodataString = repomd.objectToXml()
        ByteArrayInputStream((xmlRepodataString.toByteArray())).use { xmlRepodataInputStream ->
            val xmlRepodataArtifact = ArtifactFileFactory.build(xmlRepodataInputStream)
            // 保存repodata 节点
            val xmlRepomdNode = xmlNodeCreate(
                context.userId,
                context.repositoryInfo,
                "$SLASH${repodataUri.repodataPath}$REPODATA${SLASH}repomd.xml",
                xmlRepodataArtifact
            )
            storageService.store(xmlRepomdNode.sha256!!, xmlRepodataArtifact, context.storageCredentials)
            nodeClient.create(xmlRepomdNode)
            xmlRepodataArtifact.delete()
        }
    }

    @Transactional(rollbackFor = [Throwable::class])
    override fun onUpload(context: ArtifactUploadContext) {
        val overwrite = HttpContextHolder.getRequest().getHeader("X-BKREPO-OVERWRITE").orEmpty().let {
            if (it.isBlank()) {
                true
            } else {
                it.toBoolean()
            }
        }
        val artifactFormat = getArtifactFormat(context)
        // 检查请求路径是否契合仓库repodataDepth 深度设置
        val rpmRepoConf = getRpmRepoConf(context)
        val mark: Boolean = checkRequestUri(context, rpmRepoConf.repodataDepth)
        val nodeCreateRequest = if (mark) {
            val repeat = checkRepeatArtifact(context)
            if (repeat != FULLPATH_SHA256 && artifactFormat == RPM) {
                val rpmVersion = indexer(context, repeat, rpmRepoConf)
                rpmNodeCreateRequest(context, rpmVersion.toMetadata(), overwrite)
            } else {
                storeGroupFile(context, repeat, rpmRepoConf)
                rpmNodeCreateRequest(context, mutableMapOf(), overwrite)
            }
        } else {
            rpmNodeCreateRequest(context, mutableMapOf(), overwrite)
        }
        storageService.store(nodeCreateRequest.sha256!!, context.getArtifactFile(), context.storageCredentials)
        with(context.artifactInfo) { logger.info("Success to store $projectId/$repoName/$artifactUri") }
        nodeClient.create(nodeCreateRequest)
        logger.info("Success to insert $nodeCreateRequest")
        successUpload(context, mark, rpmRepoConf.repodataDepth)
    }

    @Transactional(rollbackFor = [Throwable::class])
    override fun remove(context: ArtifactRemoveContext) {
        with(context.artifactInfo) {
            val nodeList = with(context.artifactInfo) {
                val projectQuery = Rule.QueryRule("projectId", projectId)
                val repositoryQuery = Rule.QueryRule("repoName", repoName)
                val fullPathQuery = Rule.QueryRule("fullPath", artifactUri)

                val queryRule = Rule.NestedRule(
                    mutableListOf(projectQuery, repositoryQuery, fullPathQuery),
                    Rule.NestedRule.RelationType.AND
                )
                val queryModel = QueryModel(
                    page = PageLimit(0, 5),
                    sort = Sort(listOf("lastModifiedDate"), Sort.Direction.DESC),
                    select = mutableListOf("projectId", "repoName", "path", "name", "folder", "metadata"),
                    rule = queryRule
                )
                nodeClient.query(queryModel).data?.records
                    ?: throw ArtifactNotFoundException("Artifact[${context.artifactInfo}] not found")
            }
            if (nodeList.isEmpty()) {
                deleteFailed(context, "未找到该构件或已经被删除")
                return
            }
            val node = nodeList.first()
            if (node["folder"] as Boolean) {
                throw UnsupportedMethodException("Delete folder is forbidden")
            }
            val nodeMetadata = node["metadata"] as Map<String, String>
            val rpmVersion = RpmVersion(
                nodeMetadata["name"] ?: throw RpmArtifactMetadataResolveException(
                    "$artifactUri: not found " +
                        "metadata.name value"
                ),
                nodeMetadata["arch"] ?: throw RpmArtifactMetadataResolveException(
                    "$artifactUri: not found " +
                        "metadata.arch value"
                ),
                nodeMetadata["epoch"] ?: throw RpmArtifactMetadataResolveException(
                    "$artifactUri: not found " +
                        "metadata.epoch value"
                ),
                nodeMetadata["ver"] ?: throw RpmArtifactMetadataResolveException(
                    "$artifactUri: not found " +
                        "metadata.ver value"
                ),
                nodeMetadata["rel"] ?: throw RpmArtifactMetadataResolveException(
                    "$artifactUri: not found " +
                        "metadata.rel value"
                )
            )
            val artifactUri = context.artifactInfo.artifactUri
            // 定位对应请求的索引目录
            val rpmRepoConf = getRpmRepoConf(context)
            val repodataDepth = rpmRepoConf.repodataDepth
            val repodataUri = XmlStrUtils.splitUriByDepth(context.artifactInfo.artifactUri, repodataDepth)
            val repodataPath = repodataUri.repodataPath

            // 更新 primary, others
            deleteIndexXml(context, rpmVersion, repodataPath, PRIMARY)
            deleteIndexXml(context, rpmVersion, repodataPath, OTHERS)

            val nodeDeleteRequest = NodeDeleteRequest(projectId, repoName, artifactUri, context.userId)
            nodeClient.delete(nodeDeleteRequest)
            flushRepoMdXML(context)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(RpmLocalRepository::class.java)
    }
}

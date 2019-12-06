package com.tencent.bkrepo.pypi.artifact.repository

import com.tencent.bkrepo.common.artifact.repository.virtual.VirtualRepository
import com.tencent.bkrepo.common.artifact.repository.context.ArtifactDownloadContext
import com.tencent.bkrepo.common.artifact.repository.context.ArtifactUploadContext
import org.springframework.stereotype.Component
import java.io.File

/**
 *
 * @author: carrypan
 * @date: 2019/12/4
 */
@Component
class PypiVirtualRepository : VirtualRepository() {
    override fun onUpload(context: ArtifactUploadContext) {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }

    override fun onDownload(context: ArtifactDownloadContext): File? {
        TODO("not implemented") // To change body of created functions use File | Settings | File Templates.
    }
}

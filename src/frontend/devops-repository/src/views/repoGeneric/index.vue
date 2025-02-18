<template>
    <div class="repo-generic-container">
        <header class="mb10 pl20 pr20 generic-header flex-align-center">
            <Icon class="generic-img" size="30" name="generic" />
            <div class="ml10 generic-title">
                <div class="repo-title text-overflow" :title="replaceRepoName(repoName)">
                    {{ replaceRepoName(repoName) }}
                </div>
            </div>
        </header>
        <div class="repo-generic-main flex-align-center"
            :style="{ 'margin-left': `${searchFileName ? -(sideBarWidth + moveBarWidth) : 0}px` }">
            <div class="repo-generic-side"
                :style="{ 'flex-basis': `${sideBarWidth}px` }"
                v-bkloading="{ isLoading: treeLoading }">
                <div class="pt10 pb10 pl20 pr20">
                    <bk-input
                        v-model.trim="importantSearch"
                        placeholder="请输入关键字，按Enter键搜索"
                        clearable
                        right-icon="bk-icon icon-search"
                        @enter="searchFile"
                        @clear="searchFile">
                    </bk-input>
                </div>
                <repo-tree
                    class="repo-generic-tree"
                    ref="repoTree"
                    :tree="genericTree"
                    :important-search="importantSearch"
                    :open-list="sideTreeOpenList"
                    :selected-node="selectedTreeNode"
                    @icon-click="iconClickHandler"
                    @item-click="itemClickHandler">
                    <template #operation="{ item }">
                        <operation-list
                            v-if="item.roadMap === selectedTreeNode.roadMap"
                            :list="[
                                item.roadMap !== '0' && { clickEvent: () => showDetail(item), label: $t('detail') },
                                permission.write && repoName !== 'pipeline' && { clickEvent: () => addFolder(item), label: '新建文件夹' },
                                permission.write && repoName !== 'pipeline' && { clickEvent: () => handlerUpload(item), label: '上传文件' }
                            ]">
                        </operation-list>
                    </template>
                </repo-tree>
            </div>
            <move-split-bar
                :left="sideBarWidth"
                :width="moveBarWidth"
                @change="changeSideBarWidth"
            />
            <div class="repo-generic-table" v-bkloading="{ isLoading }">
                <div class="multi-operation flex-between-center">
                    <bk-input
                        class="w250"
                        v-if="searchFileName"
                        v-model.trim="importantSearch"
                        placeholder="请输入关键字，按Enter键搜索"
                        clearable
                        right-icon="bk-icon icon-search"
                        @enter="searchFile"
                        @clear="searchFile">
                    </bk-input>
                    <breadcrumb v-else :list="breadcrumb"></breadcrumb>
                    <div class="repo-generic-actions bk-button-group">
                        <bk-button
                            v-if="multiSelect.length"
                            @click="handlerMultiDelete()">
                            批量删除
                        </bk-button>
                        <bk-button class="ml10"
                            @click="getArtifactories">
                            {{ $t('refresh') }}
                        </bk-button>
                    </div>
                </div>
                <bk-table
                    :data="artifactoryList"
                    height="calc(100% - 100px)"
                    :outer-border="false"
                    :row-border="false"
                    size="small"
                    @row-dblclick="openFolder"
                    @selection-change="selectMultiRow">
                    <template #empty>
                        <empty-data :is-loading="isLoading" :search="Boolean(searchFileName)"></empty-data>
                    </template>
                    <bk-table-column type="selection" width="60"></bk-table-column>
                    <bk-table-column :label="$t('fileName')" prop="name" show-overflow-tooltip :render-header="renderHeader">
                        <template #default="{ row }">
                            <Icon class="table-svg mr5" size="16" :name="row.folder ? 'folder' : getIconName(row.name)" />
                            <span
                                class="hover-btn disabled"
                                v-if="!row.folder && row.metadata.forbidStatus"
                                v-bk-tooltips="{ content: tooltipContent(row.metadata), placements: ['top'] }"
                            >{{row.name}}</span>
                            <span v-else>{{ row.name }}</span>
                            <scan-tag class="mr5 table-svg"
                                v-if="showRepoScan(row)"
                                :status="row.metadata.scanStatus"
                                repo-type="generic"
                                :full-path="row.fullPath">
                            </scan-tag>
                        </template>
                    </bk-table-column>

                    <bk-table-column :label="$t('metadata')">
                        <template #default="{ row }">
                            <metadata-tag :metadata="row.nodeMetadata" :metadata-label-list="metadataLabelList" />
                        </template>
                    </bk-table-column>

                    <bk-table-column v-if="searchFileName" :label="$t('path')" prop="fullPath" show-overflow-tooltip></bk-table-column>
                    <bk-table-column :label="$t('lastModifiedDate')" prop="lastModifiedDate" width="150" :render-header="renderHeader">
                        <template #default="{ row }">{{ formatDate(row.lastModifiedDate) }}</template>
                    </bk-table-column>
                    <bk-table-column :label="$t('lastModifiedBy')" width="90" show-overflow-tooltip>
                        <template #default="{ row }">
                            {{ userList[row.lastModifiedBy] ? userList[row.lastModifiedBy].name : row.lastModifiedBy }}
                        </template>
                    </bk-table-column>
                    <bk-table-column :label="$t('size')" width="90" show-overflow-tooltip>
                        <template #default="{ row }">
                            <bk-button text
                                v-if="row.folder && !('folderSize' in row)"
                                :disabled="row.sizeLoading"
                                @click="calculateFolderSize(row)">{{ $t('calculate') }}</bk-button>
                            <span v-else>
                                {{ convertFileSize(row.size || row.folderSize || 0) }}
                            </span>
                        </template>
                    </bk-table-column>
                    <bk-table-column :label="$t('operation')" width="70">
                        <template #default="{ row }">
                            <operation-list
                                :list="[
                                    { clickEvent: () => showDetail(row), label: $t('detail') },
                                    !row.folder && getBtnDisabled(row.name) && { clickEvent: () => handlerPreviewBasicsFile(row), label: $t('preview') }, //基本类型文件 eg: txt
                                    !row.folder && baseCompressedType.includes(row.name.slice(-3)) && { clickEvent: () => handlerPreviewCompressedFile(row), label: $t('preview') }, //压缩文件 eg: rar|zip|gz|tgz|tar|jar
                                    ...(!row.metadata.forbidStatus ? [
                                        { clickEvent: () => handlerDownload(row), label: $t('download') },
                                        ...(repoName !== 'pipeline' ? [
                                            permission.edit && { clickEvent: () => renameRes(row), label: $t('rename') },
                                            permission.write && { clickEvent: () => moveRes(row), label: $t('move') },
                                            permission.write && { clickEvent: () => copyRes(row), label: $t('copy') }
                                        ] : []),
                                        ...(!row.folder ? [
                                            !community && { clickEvent: () => handlerShare(row), label: $t('share') },
                                            showRepoScan(row) && { clickEvent: () => handlerScan(row), label: '扫描制品' }
                                        ] : [])
                                    ] : []),
                                    !row.folder && { clickEvent: () => handlerForbid(row), label: row.metadata.forbidStatus ? '解除禁止' : '禁止使用' },
                                    permission.delete && repoName !== 'pipeline' && { clickEvent: () => deleteRes(row), label: $t('delete') }
                                ]">
                            </operation-list>
                        </template>
                    </bk-table-column>
                </bk-table>
                <bk-pagination
                    class="p10"
                    size="small"
                    align="right"
                    @change="current => handlerPaginationChange({ current })"
                    @limit-change="limit => handlerPaginationChange({ limit })"
                    :current.sync="pagination.current"
                    :limit="pagination.limit"
                    :count="pagination.count"
                    :limit-list="pagination.limitList">
                </bk-pagination>
            </div>
        </div>

        <generic-detail ref="genericDetail"></generic-detail>
        <generic-form-dialog ref="genericFormDialog" @refresh="refreshNodeChange"></generic-form-dialog>
        <generic-share-dialog ref="genericShareDialog"></generic-share-dialog>
        <generic-tree-dialog ref="genericTreeDialog" @update="updateGenericTreeNode" @refresh="refreshNodeChange"></generic-tree-dialog>
        <generic-upload-dialog ref="genericUploadDialog" @update="getArtifactories"></generic-upload-dialog>
        <preview-basic-file-dialog ref="previewBasicFileDialog"></preview-basic-file-dialog>
        <compressed-file-table ref="compressedFileTable" :data="compressedData" @show-preview="handleShowPreview"></compressed-file-table>
    </div>
</template>
<script>
    import OperationList from '@repository/components/OperationList'
    import Breadcrumb from '@repository/components/Breadcrumb'
    import MoveSplitBar from '@repository/components/MoveSplitBar'
    import RepoTree from '@repository/components/RepoTree'
    import ScanTag from '@repository/views/repoScan/scanTag'
    import metadataTag from '@repository/views/repoCommon/metadataTag'
    import genericDetail from '@repository/views/repoGeneric/genericDetail'
    import genericUploadDialog from '@repository/views/repoGeneric/genericUploadDialog'
    import genericFormDialog from '@repository/views/repoGeneric/genericFormDialog'
    import genericShareDialog from '@repository/views/repoGeneric/genericShareDialog'
    import genericTreeDialog from '@repository/views/repoGeneric/genericTreeDialog'
    import previewBasicFileDialog from './previewBasicFileDialog'
    import compressedFileTable from './compressedFileTable'
    import { convertFileSize, formatDate } from '@repository/utils'
    import { getIconName } from '@repository/store/publicEnum'
    import { mapState, mapMutations, mapActions } from 'vuex'

    export default {
        name: 'repoGeneric',
        components: {
            OperationList,
            Breadcrumb,
            MoveSplitBar,
            RepoTree,
            ScanTag,
            metadataTag,
            genericDetail,
            genericUploadDialog,
            genericFormDialog,
            genericShareDialog,
            genericTreeDialog,
            previewBasicFileDialog,
            compressedFileTable
        },
        data () {
            return {
                MODE_CONFIG,
                sideBarWidth: 300,
                moveBarWidth: 10,
                isLoading: false,
                treeLoading: false,
                importantSearch: this.$route.query.fileName,
                // 搜索路径文件夹下的内容
                searchFullPath: '',
                // 左侧树处于打开状态的目录
                sideTreeOpenList: [],
                sortType: 'lastModifiedDate',
                // 中间展示的table数据
                artifactoryList: [],
                multiSelect: [],
                // 左侧树选中的节点
                selectedTreeNode: {},
                // 分页信息
                pagination: {
                    count: 0,
                    current: 1,
                    limit: 20,
                    limitList: [10, 20, 40]
                },
                baseCompressedType: ['rar', 'zip', 'gz', 'tgz', 'tar', 'jar'],
                compressedData: [],
                metadataLabelList: []
            }
        },
        computed: {
            ...mapState(['repoListAll', 'userList', 'permission', 'genericTree', 'scannerSupportFileNameExt']),
            projectId () {
                return this.$route.params.projectId
            },
            repoName () {
                return this.$route.query.repoName
            },
            currentRepo () {
                return this.repoListAll.find(repo => repo.name === this.repoName) || {}
            },
            breadcrumb () {
                if (!this.selectedTreeNode.roadMap) return
                const breadcrumb = []
                let node = this.genericTree
                const road = this.selectedTreeNode.roadMap.split(',')
                road.forEach(index => {
                    breadcrumb.push({
                        name: node[index].displayName,
                        value: node[index],
                        cilckHandler: item => {
                            this.itemClickHandler(item.value)
                        }
                    })
                    node = node[index].children
                })
                return breadcrumb
            },
            community () {
                return RELEASE_MODE === 'community'
            },
            searchFileName () {
                return this.$route.query.fileName
            }
        },
        beforeRouteEnter (to, from, next) {
            // 前端隐藏report仓库/log仓库
            if (MODE_CONFIG === 'ci' && (to.query.repoName === 'report' || to.query.repoName === 'log')) {
                next({
                    name: 'repoList',
                    params: {
                        projectId: to.params.projectId
                    }
                })
            } else next()
        },
        created () {
            this.getRepoListAll({ projectId: this.projectId })
            this.initPage()
            if (!this.community) {
                this.refreshSupportFileNameExtList()
            }
        },
        methods: {
            convertFileSize,
            getIconName,
            formatDate,
            ...mapMutations(['INIT_TREE']),
            ...mapActions([
                'getRepoListAll',
                'getFolderList',
                'getArtifactoryList',
                'getMetadataLabelList',
                'deleteArtifactory',
                'deleteMultiArtifactory',
                'getFolderSize',
                'getFileNumOfFolder',
                'getMultiFileNumOfFolder',
                'previewBasicFile',
                'previewCompressedBasicFile',
                'previewCompressedFileList',
                'forbidMetadata',
                'refreshSupportFileNameExtList'
            ]),
            showRepoScan (node) {
                const indexOfLastDot = node.name.lastIndexOf('.')
                let supportFileNameExt = false
                if (indexOfLastDot === -1) {
                    supportFileNameExt = this.scannerSupportFileNameExt.includes('')
                } else {
                    supportFileNameExt = this.scannerSupportFileNameExt.includes(node.name.substring(indexOfLastDot + 1))
                }
                return !node.folder && !this.community && supportFileNameExt
            },
            tooltipContent ({ forbidType, forbidUser }) {
                switch (forbidType) {
                    case 'SCANNING':
                        return '制品正在扫描中'
                    case 'QUALITY_UNPASS':
                        return '制品扫描质量规则未通过'
                    case 'MANUAL':
                        return `${this.userList[forbidUser]?.name || forbidUser} 手动禁止`
                    default:
                        return ''
                }
            },
            changeSideBarWidth (sideBarWidth) {
                if (sideBarWidth > 260) {
                    this.sideBarWidth = sideBarWidth
                }
            },
            renderHeader (h, { column }) {
                return h('div', {
                    class: {
                        'flex-align-center hover-btn': true,
                        'selected-header': this.sortType === column.property
                    },
                    on: {
                        click: () => {
                            this.sortType = column.property
                            this.handlerPaginationChange()
                        }
                    }
                }, [
                    h('span', column.label),
                    h('i', {
                        class: 'ml5 devops-icon icon-down-shape'
                    })
                ])
            },
            initPage () {
                this.INIT_TREE([{
                    name: this.replaceRepoName(this.repoName),
                    displayName: this.replaceRepoName(this.repoName),
                    fullPath: '',
                    folder: true,
                    children: [],
                    roadMap: '0'
                }])

                const paths = (this.$route.query.path || '').split('/').filter(Boolean)
                paths.pop() // 定位到文件/文件夹的上级目录
                paths.reduce(async (chain, path) => {
                    const node = await chain
                    if (!node) return
                    await this.updateGenericTreeNode(node)
                    const child = node.children.find(child => child.name === path)
                    if (!child) {
                        this.$bkMessage({
                            theme: 'error',
                            message: '文件路径不存在'
                        })
                        return
                    }
                    this.sideTreeOpenList.push(child.roadMap)
                    return child
                }, Promise.resolve(this.genericTree[0])).then(node => {
                    this.itemClickHandler(node || this.genericTree[0])
                })
            },
            // 获取中间列表数据
            async getArtifactories () {
                this.isLoading = true

                const metadataLabelList = await this.getMetadataLabelList({
                    projectId: this.projectId
                })
                this.metadataLabelList = metadataLabelList

                this.getArtifactoryList({
                    projectId: this.projectId,
                    repoName: this.repoName,
                    fullPath: this.selectedTreeNode.fullPath,
                    ...(this.searchFullPath
                        ? {
                            fullPath: this.searchFullPath
                        }
                        : {
                            name: this.searchFileName
                        }
                    ),
                    current: this.pagination.current,
                    limit: this.pagination.limit,
                    sortType: this.sortType,
                    isPipeline: this.repoName === 'pipeline'
                }).then(({ records, totalRecords }) => {
                    this.pagination.count = totalRecords
                    this.artifactoryList = records.map(v => {
                        v.nodeMetadata.forEach(item => {
                            metadataLabelList.forEach(ele => {
                                if (ele.labelKey === item.key) {
                                    item.display = ele.display
                                }
                            })
                        })

                        return {
                            metadata: {},
                            ...v,
                            // 流水线文件夹名称替换
                            name: v.metadata?.displayName || v.name
                        }
                    })
                }).finally(() => {
                    this.isLoading = false
                })
            },
            searchFile () {
                if (this.importantSearch || this.searchFileName) {
                    this.$router.replace({
                        query: {
                            ...this.$route.query,
                            fileName: this.importantSearch
                        }
                    })
                    this.searchFullPath = ''
                    this.handlerPaginationChange()
                }
            },
            handlerPaginationChange ({ current = 1, limit = this.pagination.limit } = {}) {
                this.pagination.current = current
                this.pagination.limit = limit
                this.getArtifactories()
            },
            // 树组件选中文件夹
            itemClickHandler (node) {
                this.selectedTreeNode = node

                this.handlerPaginationChange()
                // 更新已展开文件夹数据
                const reg = new RegExp(`^${node.roadMap}`)
                const openList = this.sideTreeOpenList
                openList.splice(0, openList.length, ...openList.filter(v => !reg.test(v)))
                // 打开选中节点的左侧树的所有祖先节点
                node.roadMap.split(',').forEach((v, i, arr) => {
                    const roadMap = arr.slice(0, i + 1).join(',')
                    !openList.includes(roadMap) && openList.push(roadMap)
                })
                // 更新子文件夹
                if (node.loading) return
                this.updateGenericTreeNode(node)

                // 更新url参数
                this.$router.replace({
                    query: {
                        ...this.$route.query,
                        path: `${node.fullPath}/default`
                    }
                })
            },
            iconClickHandler (node) {
                // 更新已展开文件夹数据
                const reg = new RegExp(`^${node.roadMap}`)
                const openList = this.sideTreeOpenList
                if (openList.includes(node.roadMap)) {
                    openList.splice(0, openList.length, ...openList.filter(v => !reg.test(v)))
                } else {
                    openList.push(node.roadMap)
                    // 更新子文件夹
                    if (node.loading) return
                    // 当前选中文件夹为当前操作文件夹的后代文件夹，则锁定文件夹保证选中文件夹路径完整
                    if (node.roadMap !== this.selectedTreeNode.roadMap && reg.test(this.selectedTreeNode.roadMap)) return
                    this.updateGenericTreeNode(node)
                }
            },
            updateGenericTreeNode (item) {
                this.$set(item, 'loading', true)
                return this.getFolderList({
                    projectId: this.projectId,
                    repoName: this.repoName,
                    fullPath: item.fullPath,
                    roadMap: item.roadMap,
                    isPipeline: this.repoName === 'pipeline'
                }).finally(() => {
                    this.$set(item, 'loading', false)
                })
            },
            // 双击table打开文件夹
            openFolder (row) {
                if (!row.folder) return
                if (this.searchFileName) {
                    // 搜索中打开文件夹
                    this.searchFullPath = row.fullPath
                    this.handlerPaginationChange()
                } else {
                    const node = this.selectedTreeNode.children.find(v => v.fullPath === row.fullPath)
                    this.itemClickHandler(node)
                }
            },
            showDetail ({ folder, fullPath }) {
                this.$refs.genericDetail.setData({
                    show: true,
                    loading: false,
                    projectId: this.projectId,
                    repoName: this.repoName,
                    folder,
                    path: fullPath,
                    data: {},
                    metadataLabelList: this.metadataLabelList
                })
            },
            renameRes ({ name, fullPath }) {
                this.$refs.genericFormDialog.setData({
                    show: true,
                    loading: false,
                    type: 'rename',
                    name,
                    path: fullPath,
                    title: `${this.$t('rename')} (${name})`
                })
            },
            addFolder ({ fullPath }) {
                this.$refs.genericFormDialog.setData({
                    show: true,
                    loading: false,
                    type: 'add',
                    path: fullPath + '/',
                    title: `${this.$t('create') + this.$t('folder')}`
                })
            },
            handlerScan ({ name, fullPath }) {
                this.$refs.genericFormDialog.setData({
                    show: true,
                    loading: false,
                    title: '扫描制品',
                    type: 'scan',
                    id: '',
                    name,
                    path: fullPath
                })
            },
            refreshNodeChange () {
                this.updateGenericTreeNode(this.selectedTreeNode)
                this.getArtifactories()
            },
            handlerShare ({ name, fullPath }) {
                this.$refs.genericShareDialog.setData({
                    projectId: this.projectId,
                    repoName: this.repoName,
                    show: true,
                    loading: false,
                    title: `${this.$t('share')} (${name})`,
                    path: fullPath,
                    user: [],
                    ip: [],
                    permits: '',
                    time: 7
                })
            },
            async deleteRes ({ name, folder, fullPath }) {
                if (!fullPath) return
                let totalRecords
                if (folder) {
                    totalRecords = await this.getFileNumOfFolder({
                        projectId: this.projectId,
                        repoName: this.repoName,
                        fullPath
                    })
                }
                this.$confirm({
                    theme: 'danger',
                    message: `${this.$t('confirm') + this.$t('delete')}${folder ? this.$t('folder') : this.$t('file')} ${name} ？`,
                    subMessage: `${folder && totalRecords ? `当前文件夹下存在${totalRecords}个文件` : ''}`,
                    confirmFn: () => {
                        return this.deleteArtifactory({
                            projectId: this.projectId,
                            repoName: this.repoName,
                            fullPath
                        }).then(() => {
                            this.refreshNodeChange()
                            this.$bkMessage({
                                theme: 'success',
                                message: this.$t('delete') + this.$t('success')
                            })
                        })
                    }
                })
            },
            moveRes ({ name, fullPath }) {
                this.$refs.genericTreeDialog.setTreeData({
                    show: true,
                    type: 'move',
                    title: `${this.$t('move')} (${name})`,
                    path: fullPath
                })
            },
            copyRes ({ name, fullPath }) {
                this.$refs.genericTreeDialog.setTreeData({
                    show: true,
                    type: 'copy',
                    title: `${this.$t('copy')} (${name})`,
                    path: fullPath
                })
            },
            handlerUpload ({ fullPath }) {
                this.$refs.genericUploadDialog.setData({
                    show: true,
                    title: `${this.$t('upload')} (${fullPath || '/'})`,
                    fullPath: fullPath
                })
            },
            handlerDownload ({ fullPath }) {
                const url = `/generic/${this.projectId}/${this.repoName}/${fullPath}?download=true`
                this.$ajax.head(url).then(() => {
                    window.open(
                        '/web' + url,
                        '_self'
                    )
                }).catch(e => {
                    const message = e.status === 403 ? this.$t('fileDownloadError', [this.$route.params.projectId]) : this.$t('fileError')
                    this.$bkMessage({
                        theme: 'error',
                        message
                    })
                })
            },
            handlerForbid ({ fullPath, metadata: { forbidStatus } }) {
                this.forbidMetadata({
                    projectId: this.projectId,
                    repoName: this.repoName,
                    fullPath,
                    body: {
                        nodeMetadata: [{ key: 'forbidStatus', value: !forbidStatus }]
                    }
                }).then(() => {
                    this.$bkMessage({
                        theme: 'success',
                        message: (forbidStatus ? '解除禁止' : '禁止使用') + this.$t('success')
                    })
                    this.getArtifactories()
                })
            },
            calculateFolderSize (row) {
                this.$set(row, 'sizeLoading', true)
                this.getFolderSize({
                    projectId: this.projectId,
                    repoName: this.repoName,
                    fullPath: row.fullPath
                }).then(({ size }) => {
                    this.$set(row, 'folderSize', size)
                }).finally(() => {
                    this.$set(row, 'sizeLoading', false)
                })
            },
            selectMultiRow (selects) {
                this.multiSelect = selects
            },
            async handlerMultiDelete () {
                const paths = this.multiSelect.map(r => r.fullPath)
                const totalRecords = await this.getMultiFileNumOfFolder({
                    projectId: this.projectId,
                    repoName: this.repoName,
                    paths
                })
                this.$confirm({
                    theme: 'danger',
                    message: `确认批量删除已选中的 ${this.multiSelect.length} 项？`,
                    subMessage: `选中文件夹和文件共计包含 ${totalRecords} 个文件`,
                    confirmFn: () => {
                        return this.deleteMultiArtifactory({
                            projectId: this.projectId,
                            repoName: this.repoName,
                            paths
                        }).then(() => {
                            this.refreshNodeChange()
                            this.$bkMessage({
                                theme: 'success',
                                message: this.$t('delete') + this.$t('success')
                            })
                        })
                    }
                })
            },
            async handlerPreviewBasicsFile (row) {
                this.$refs.previewBasicFileDialog.setDialogData({
                    show: true,
                    title: row.name,
                    isLoading: true
                })
                const res = await this.previewBasicFile({
                    projectId: row.projectId,
                    repoName: row.repoName,
                    path: row.fullPath
                })
                this.$refs.previewBasicFileDialog.setData(typeof (res) === 'string' ? res : JSON.stringify(res))
            },
            async handlerPreviewCompressedFile (row) {
                if (row.size > 1073741824) {
                    this.$bkMessage({
                        theme: 'error',
                        message: this.$t('previewCompressedLimitTips')
                    })
                    return
                }
                this.$refs.compressedFileTable.setData({
                    show: true,
                    title: row.name,
                    isLoading: true,
                    path: row.fullPath
                })

                const res = await this.previewCompressedFileList({
                    projectId: row.projectId,
                    repoName: row.repoName,
                    path: row.fullPath
                })

                this.compressedData = res.reduce((acc, item) => {
                    const names = item.name.split('/')
                    names.reduce((target, name) => {
                        let temp = target.find(o => o.name === name)
                        if (!temp) {
                            target.push(temp = { name, children: [], filePath: item.name, folder: !name.includes('.'), size: item.size })
                        }
                        return temp.children
                    }, acc)
                    return acc
                }, [])
            },

            async handleShowPreview (row) {
                const { projectId, repoName, path, filePath } = row
                this.$refs.previewBasicFileDialog.setDialogData({
                    show: true,
                    title: filePath,
                    isLoading: true
                })
                const res = await this.previewCompressedBasicFile({
                    projectId,
                    repoName,
                    path,
                    filePath
                })
                this.$refs.previewBasicFileDialog.setData(typeof (res) === 'string' ? res : JSON.stringify(res))
            },

            getBtnDisabled (name) {
                return name.endsWith('txt')
                    || name.endsWith('sh')
                    || name.endsWith('bat')
                    || name.endsWith('json')
                    || name.endsWith('yaml')
                    || name.endsWith('xml')
                    || name.endsWith('log')
                    || name.endsWith('ini')
                    || name.endsWith('log')
                    || name.endsWith('properties')
                    || name.endsWith('toml')
            }
        }
    }
</script>
<style lang="scss" scoped>
.repo-generic-container {
    height: 100%;
    overflow: hidden;
    .generic-header{
        height: 60px;
        background-color: white;
        .generic-img {
            border-radius: 4px;
        }
        .generic-title {
            .repo-title {
                max-width: 500px;
                font-size: 16px;
                font-weight: 500;
                color: #081E40;
            }
            // .repo-description {
            //     max-width: 70vw;
            //     padding: 5px 15px;
            //     background-color: var(--bgWeightColor);
            //     border-radius: 2px;
            // }
        }
    }
    .repo-generic-main {
        height: calc(100% - 100px);
        .repo-generic-side {
            height: 100%;
            overflow: hidden;
            background-color: white;
            .repo-generic-tree {
                border-top: 1px solid var(--borderColor);
                height: calc(100% - 50px);
            }
        }
        .repo-generic-table {
            flex: 1;
            height: 100%;
            background-color: white;
            .multi-operation {
                height: 50px;
                padding: 10px 20px;
            }
            ::v-deep .selected-header {
                color: var(--fontPrimaryColor);
                .icon-down-shape {
                    color: var(--primaryColor);
                }
            }
        }
    }
}

::v-deep .bk-table-row.selected-row {
    background-color: var(--bgHoverColor);
}
</style>

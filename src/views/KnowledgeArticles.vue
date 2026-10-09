<script setup>
// ===== 知识文章管理（后台）：RAG 知识库的增删改查 =====
// 新增/编辑会触发后端切块 + Ollama 向量化，提交比普通表单慢，按钮需 loading。
// 唯一数据源是后端 Redis（/api/knowledge/admin），所有写操作落库后重新拉列表。
import { ref, reactive, onMounted } from 'vue'
import { request } from '@/api/request'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Search, Refresh, UploadFilled } from '@element-plus/icons-vue'

// 与后端 DocumentTextExtractor.SUPPORTED_EXTENSIONS 保持一致
const ACCEPT_TYPES = '.pdf,.docx,.doc,.txt,.md'
const MAX_FILE_MB = 30

// 与知识库约定的四个分类
const CATEGORIES = ['情绪管理', '人际交往', '压力调节', '自我成长']

// ---------- 列表 ----------
const loading = ref(false)
const rows = ref([])
const keyword = ref('')
const appliedKeyword = ref('')

// 分页只做展示切片：total 永远等于接口返回条数
const page = ref(1)
const pageSize = ref(10)
const pagedRows = () =>
  rows.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value)

const loadList = async () => {
  loading.value = true
  try {
    const qs = appliedKeyword.value ? `?keyword=${encodeURIComponent(appliedKeyword.value)}` : ''
    rows.value = await request(`/knowledge/admin${qs}`)
    page.value = 1
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

const onSearch = () => {
  appliedKeyword.value = keyword.value.trim()
  loadList()
}
const onReset = () => {
  keyword.value = ''
  appliedKeyword.value = ''
  loadList()
}

// ---------- 新增 / 编辑弹窗 ----------
const dialogVisible = ref(false)
const dialogMode = ref('create') // create | edit
const submitting = ref(false)
const editingId = ref(null)
const formRef = ref(null)

// 新增时的两种录入方式：手动输入文本 / 上传文件；编辑只支持文本
const activeTab = ref('text')
// 文件上传表单：标题可留空（后端用文件名兜底），分类必选
const fileForm = reactive({ title: '', category: '' })
const selectedFile = ref(null) // 用户选中的 File 对象

const form = reactive({ title: '', category: '', content: '' })

// 知识层级：A 风险 / B 量表 / C 访谈 / D 干预 / E 论文（后端按 layer 字段返回）
const LAYER_LABELS = { A: 'A 风险', B: 'B 量表', C: 'C 访谈', D: 'D 干预', E: 'E 论文' }
const layerLabel = (layer) => LAYER_LABELS[layer] || 'E 论文'
const layerTagType = (layer) => ({ A: 'danger', B: 'warning', C: 'success', D: 'primary' }[layer] || 'info')
const rules = {
  title: [
    { required: true, message: '请输入标题', trigger: 'blur' },
    { max: 100, message: '标题最长 100 个字', trigger: 'blur' },
  ],
  category: [{ required: true, message: '请选择分类', trigger: 'change' }],
  content: [
    { required: true, message: '请输入正文', trigger: 'blur' },
    { max: 20000, message: '正文最长 20000 个字', trigger: 'blur' },
  ],
}

const openCreate = () => {
  dialogMode.value = 'create'
  editingId.value = null
  activeTab.value = 'text'
  form.title = ''
  form.category = ''
  form.content = ''
  fileForm.title = ''
  fileForm.category = ''
  selectedFile.value = null
  dialogVisible.value = true
}

const openEdit = (row) => {
  dialogMode.value = 'edit'
  editingId.value = row.id
  activeTab.value = 'text'
  form.title = row.title
  form.category = row.category
  form.content = row.content
  dialogVisible.value = true
}

// el-change 选中文件时：只做前端预校验，真正上传在点保存时
const onFileChange = (uploadFile) => {
  const f = uploadFile.raw
  const extOk = ACCEPT_TYPES.split(',').some(
    (ext) => f.name.toLowerCase().endsWith(ext)
  )
  if (!extOk) {
    selectedFile.value = null
    ElMessage.error('仅支持 PDF / Word(.docx,.doc) / TXT / Markdown 文件')
    return
  }
  if (f.size > MAX_FILE_MB * 1024 * 1024) {
    selectedFile.value = null
    ElMessage.error(`文件不能超过 ${MAX_FILE_MB}MB`)
    return
  }
  selectedFile.value = f
}

const onFileRemove = () => {
  selectedFile.value = null
}

const submit = async () => {
  // 编辑：走原有的文本更新接口
  if (dialogMode.value === 'edit') {
    await formRef.value.validate()
    submitting.value = true
    try {
      await request(`/knowledge/admin/${editingId.value}`, { method: 'PUT', body: { ...form } })
      ElMessage.success('已更新并重新向量化')
      dialogVisible.value = false
      loadList()
    } catch (e) {
      ElMessage.error(e.message)
    } finally {
      submitting.value = false
    }
    return
  }

  // 新增-文件上传：校验分类 + 必选文件
  if (activeTab.value === 'file') {
    if (!fileForm.category) {
      ElMessage.warning('请先选择分类')
      return
    }
    if (!selectedFile.value) {
      ElMessage.warning('请先选择要上传的文件')
      return
    }
    submitting.value = true
    try {
      const fd = new FormData()
      fd.append('file', selectedFile.value)
      fd.append('category', fileForm.category)
      // 标题留空就不 append，后端会用文件名兜底
      if (fileForm.title.trim()) fd.append('title', fileForm.title.trim())
      // 论文可能切块很多，CPU 向量化耗时较长，给足等待时间
      await request('/knowledge/admin/upload', { method: 'POST', body: fd })
      ElMessage.success('文件解析并向量化完成')
      dialogVisible.value = false
      loadList()
    } catch (e) {
      ElMessage.error(e.message)
    } finally {
      submitting.value = false
    }
    return
  }

  // 新增-手动输入
  await formRef.value.validate()
  submitting.value = true
  try {
    await request('/knowledge/admin', { method: 'POST', body: { ...form } })
    ElMessage.success('已保存并完成向量化')
    dialogVisible.value = false
    loadList()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    submitting.value = false
  }
}

// ---------- 删除 ----------
const remove = async (row) => {
  try {
    await ElMessageBox.confirm(
      `确定删除文章「${row.title}」吗？它的全部知识块和向量会一并删除，不可恢复。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await request(`/knowledge/admin/${row.id}`, { method: 'DELETE' })
    ElMessage.success('已删除')
    const lastPage = Math.max(1, Math.ceil((rows.value.length - 1) / pageSize.value))
    if (page.value > lastPage) page.value = lastPage
    loadList()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

onMounted(loadList)
</script>

<template>
  <el-card>
    <!-- 工具栏：搜索 + 新增 -->
    <div class="toolbar">
      <div class="search-box">
        <el-input
          v-model="keyword"
          placeholder="搜索标题或分类关键词"
          clearable
          style="width: 260px"
          @keyup.enter="onSearch"
          @clear="onReset"
        />
        <el-button type="primary" :icon="Search" @click="onSearch">查询</el-button>
        <el-button :icon="Refresh" @click="onReset">重置</el-button>
      </div>
      <el-button type="primary" :icon="Plus" @click="openCreate">新增文章</el-button>
    </div>

    <!-- 数据表 -->
    <el-table v-loading="loading" :data="pagedRows()" stripe style="margin-top: 16px">
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column prop="title" label="标题" show-overflow-tooltip min-width="220" />
      <el-table-column prop="layer" label="层级" width="130">
        <template #default="{ row }">
          <el-tag size="small" :type="layerTagType(row.layer)">{{ layerLabel(row.layer) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="category" label="分类" width="120">
        <template #default="{ row }">
          <el-tag size="small" effect="plain">{{ row.category }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="chunks" label="知识块数" width="100" align="center" />
      <el-table-column prop="content" label="正文预览" show-overflow-tooltip min-width="280" />
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="还没有知识文章，点右上角新增第一篇吧" />
      </template>
    </el-table>

    <!-- 分页 -->
    <div class="pager">
      <el-pagination
        background
        layout="total, prev, pager, next"
        :total="rows.length"
        :page-size="pageSize"
        v-model:current-page="page"
      />
    </div>

    <!-- 新增/编辑弹窗 -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogMode === 'create' ? '新增知识文章' : '编辑知识文章'"
      width="640px"
      @closed="formRef?.resetFields()"
    >
      <!-- 编辑模式只有文本一种方式，隐藏 tab 头（class 控制） -->
      <el-tabs v-model="activeTab" :class="{ 'hide-tabs-header': dialogMode === 'edit' }">
        <!-- ===== 方式一：手动输入文本 ===== -->
        <el-tab-pane label="手动输入" name="text">
          <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
            <el-form-item label="标题" prop="title">
              <el-input v-model="form.title" maxlength="100" show-word-limit
                placeholder="如：认识焦虑：从过度担忧到自我安抚" />
            </el-form-item>
            <el-form-item label="分类" prop="category">
              <el-select v-model="form.category" placeholder="选择分类" style="width: 200px">
                <el-option v-for="c in CATEGORIES" :key="c" :label="c" :value="c" />
              </el-select>
            </el-form-item>
            <el-form-item label="正文" prop="content">
              <el-input
                v-model="form.content"
                type="textarea"
                :rows="12"
                maxlength="20000"
                show-word-limit
                placeholder="粘贴或撰写专业文章正文。段落之间用空行分隔，入库时会按段落优先切块并做向量化。"
              />
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <!-- ===== 方式二：上传文件（仅新增） ===== -->
        <el-tab-pane v-if="dialogMode === 'create'" label="上传文件" name="file">
          <el-form label-width="80px">
            <el-form-item label="分类" required>
              <el-select v-model="fileForm.category" placeholder="选择分类" style="width: 200px">
                <el-option v-for="c in CATEGORIES" :key="c" :label="c" :value="c" />
              </el-select>
            </el-form-item>
            <el-form-item label="标题">
              <el-input v-model="fileForm.title" maxlength="100" show-word-limit
                placeholder="可留空，自动使用文件名" />
            </el-form-item>
            <el-form-item label="文件">
              <!-- auto-upload=false：选文件不立即传，点"保存"时连同分类/标题一起提交 -->
              <el-upload
                drag
                :auto-upload="false"
                :accept="ACCEPT_TYPES"
                :limit="1"
                :on-change="onFileChange"
                :on-remove="onFileRemove"
                :on-exceed="() => ElMessage.warning('一次只能上传一个文件')"
              >
                <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
                <div class="el-upload__text">
                  拖拽文件到此处，或<em>点击选择</em>
                </div>
                <template #tip>
                  <div class="upload-tip">
                    支持 PDF / Word(.docx, .doc) / TXT / Markdown，单文件不超过 {{ MAX_FILE_MB }}MB；
                    解析后正文不超过 30000 字。扫描件 PDF 无法提取文字。
                  </div>
                </template>
              </el-upload>
            </el-form-item>
          </el-form>
        </el-tab-pane>
      </el-tabs>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">
          {{ submitting ? '解析向量化中…' : dialogMode === 'create' ? '保存' : '保存修改' }}
        </el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.search-box {
  display: flex;
  gap: 8px;
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
/* 编辑模式不显示"手动输入/上传文件"切换头 */
.hide-tabs-header :deep(.el-tabs__header) {
  display: none;
}
.upload-tip {
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
  margin-top: 6px;
}
</style>

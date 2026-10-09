<script setup>
// ===== 故事集管理（后台）：心声的增删改查 =====
// 唯一数据源是后端 Redis（/api/wall/admin），所有写操作都落库后重新拉列表。
import { ref, reactive, onMounted } from 'vue'
import { request } from '@/api/request'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Search, Refresh } from '@element-plus/icons-vue'

// ---------- 列表 ----------
const loading = ref(false)
const rows = ref([])            // 接口返回的完整过滤结果（事实源）
const keyword = ref('')         // 搜索框输入
const appliedKeyword = ref('')  // 已执行查询的关键词

// 分页只做展示切片：total 永远等于接口返回条数，不臆造数据
const page = ref(1)
const pageSize = ref(10)
const pagedRows = () =>
  rows.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value)

const loadList = async () => {
  loading.value = true
  try {
    const qs = appliedKeyword.value ? `?keyword=${encodeURIComponent(appliedKeyword.value)}` : ''
    rows.value = await request(`/wall/admin${qs}`)
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

const form = reactive({ username: '', content: '' })
const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { max: 30, message: '用户名最长 30 个字', trigger: 'blur' },
  ],
  content: [
    { required: true, message: '请输入心声内容', trigger: 'blur' },
    { max: 200, message: '内容最长 200 个字', trigger: 'blur' },
  ],
}

const openCreate = () => {
  dialogMode.value = 'create'
  editingId.value = null
  form.username = ''
  form.content = ''
  dialogVisible.value = true
}

const openEdit = (row) => {
  dialogMode.value = 'edit'
  editingId.value = row.id
  form.username = row.username
  form.content = row.content
  dialogVisible.value = true
}

const submit = async () => {
  await formRef.value.validate()
  submitting.value = true
  try {
    if (dialogMode.value === 'create') {
      await request('/wall/admin', {
        method: 'POST',
        body: { username: form.username, content: form.content },
      })
      ElMessage.success('发布成功')
    } else {
      await request(`/wall/admin/${editingId.value}`, {
        method: 'PUT',
        body: { username: form.username, content: form.content },
      })
      ElMessage.success('修改成功')
    }
    dialogVisible.value = false
    loadList() // 以接口结果为准刷新
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
      `确定删除「${row.username}」的这条心声吗？删除后不可恢复。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await request(`/wall/admin/${row.id}`, { method: 'DELETE' })
    ElMessage.success('已删除')
    // 删完若当前页空了，退回上一页
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
          placeholder="搜索用户名或内容关键词"
          clearable
          style="width: 260px"
          @keyup.enter="onSearch"
          @clear="onReset"
        />
        <el-button type="primary" :icon="Search" @click="onSearch">查询</el-button>
        <el-button :icon="Refresh" @click="onReset">重置</el-button>
      </div>
      <el-button type="primary" :icon="Plus" @click="openCreate">新增心声</el-button>
    </div>

    <!-- 数据表 -->
    <el-table v-loading="loading" :data="pagedRows()" stripe style="margin-top: 16px">
      <el-table-column prop="id" label="ID" width="90" />
      <el-table-column prop="username" label="用户名" width="180" show-overflow-tooltip />
      <el-table-column prop="content" label="心声内容" show-overflow-tooltip min-width="320" />
      <el-table-column prop="likes" label="点赞数" width="100" align="center" />
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="还没有心声，点右上角发布第一条吧" />
      </template>
    </el-table>

    <!-- 分页：展示切片，total 为接口真实条数 -->
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
      :title="dialogMode === 'create' ? '新增心声' : '编辑心声'"
      width="520px"
      @closed="formRef?.resetFields()"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" maxlength="30" show-word-limit placeholder="如：晚风" />
        </el-form-item>
        <el-form-item label="内容" prop="content">
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="4"
            maxlength="200"
            show-word-limit
            placeholder="写下这条心声……"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">
          {{ dialogMode === 'create' ? '发布' : '保存' }}
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
</style>

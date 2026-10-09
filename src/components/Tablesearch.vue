<script setup>
// ===== 表格搜索组件：人员ID + 类别（下拉）+ 状态（下拉）+ 查询/重置 =====
import { reactive } from 'vue'
import { Search, Refresh } from '@element-plus/icons-vue'

// 下拉选项支持两种写法：['选项A'] 或 [{ label: '选项A', value: 'a' }]
const props = defineProps({
  // 类别下拉选项（各页面传入自己的分类）
  categoryOptions: {
    type: Array,
    default: () => [],
  },
  // 状态下拉选项（各页面可覆盖，给一套通用默认值）
  statusOptions: {
    type: Array,
    default: () => ['正常', '停用'],
  },
})

const emit = defineEmits(['search', 'reset'])

// 筛选条件：空字符串表示“不限”
const form = reactive({
  userId: '',
  category: '',
  status: '',
})

// 统一成 { label, value } 结构供 el-option 使用
const normalize = (opts) =>
  opts.map((o) => (typeof o === 'string' ? { label: o, value: o } : o))

const onSearch = () => emit('search', { ...form })
const onReset = () => {
  form.userId = ''
  form.category = ''
  form.status = ''
  emit('reset')
}
</script>

<template>
  <el-form :inline="true" class="table-search" @submit.prevent>
    <!-- 人员ID：输入框，支持回车直接查询 -->
    <el-form-item label="人员ID">
      <el-input
        v-model="form.userId"
        placeholder="请输入人员ID"
        clearable
        class="field"
        @keyup.enter="onSearch"
      />
    </el-form-item>

    <!-- 类别：下拉选择 -->
    <el-form-item label="类别">
      <el-select
        v-model="form.category"
        placeholder="请选择类别"
        clearable
        class="field"
      >
        <el-option
          v-for="o in normalize(props.categoryOptions)"
          :key="o.value"
          :label="o.label"
          :value="o.value"
        />
      </el-select>
    </el-form-item>

    <!-- 状态：下拉选择 -->
    <el-form-item label="状态">
      <el-select
        v-model="form.status"
        placeholder="请选择状态"
        clearable
        class="field"
      >
        <el-option
          v-for="o in normalize(props.statusOptions)"
          :key="o.value"
          :label="o.label"
          :value="o.value"
        />
      </el-select>
    </el-form-item>

    <el-form-item>
      <el-button type="primary" :icon="Search" @click="onSearch">查询</el-button>
      <el-button :icon="Refresh" @click="onReset">重置</el-button>
    </el-form-item>
  </el-form>
</template>

<style scoped>
.table-search {
  display: flex;
  flex-wrap: wrap;
  margin-bottom: 8px;
}
/* 三个筛选项统一宽度 */
.field {
  width: 200px;
}
</style>

<script setup>
// ===== 侧边菜单折叠按钮：放在顶栏最左边 =====
import { Fold, Expand } from '@element-plus/icons-vue'

// 当前是否已折叠，决定显示哪个图标
defineProps({
  isCollapse: {
    type: Boolean,
    default: false,
  },
})

const emit = defineEmits(['toggle'])
</script>

<template>
  <el-button
    text
    class="navbar-btn"
    @click="emit('toggle')"
  >
    <el-icon :size="20">
      <!-- 已折叠显示展开图标，未折叠显示收起图标 -->
      <component :is="isCollapse ? Expand : Fold" />
    </el-icon>
  </el-button>
</template>

<style scoped>
.navbar-btn {
  padding: 4px 8px;
  margin-right: 12px;
}
</style>

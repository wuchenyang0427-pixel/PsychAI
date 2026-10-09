<script setup>
// ===== AI 心理助手对话页（DeepSeek 风格）=====
// 已接入真实大模型：前端只管发消息，回复由后端调 DeepSeek 生成。
import { ref, computed, nextTick, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Promotion, Document, SwitchButton } from '@element-plus/icons-vue'
import { request, requestStream } from '@/api/request'
import { ElMessage, ElMessageBox } from 'element-plus'
import { marked } from 'marked'
import DOMPurify from 'dompurify'

const router = useRouter()

// 对话消息：role = user(右侧气泡) / assistant(左侧气泡)
const messages = ref([])
const input = ref('')
const thinking = ref(false) // 助手"正在输入"状态
const listRef = ref()

// ===== 本地持久化：路由切走组件会被销毁，消息只存在内存里就丢了 =====
// 按用户 ID 隔离存储（和后端记忆一样，绝不串号），刷新页面/切换菜单都能恢复
const currentUserId = () =>
  JSON.parse(localStorage.getItem('app_user') || '{}').username || 'guest'
const storageKey = () => `chat_messages_${currentUserId()}`

const loadMessages = () => {
  try {
    const saved = JSON.parse(localStorage.getItem(storageKey()) || '[]')
    if (Array.isArray(saved)) {
      messages.value = saved
    }
  } catch {
    // 本地数据损坏时按空对话处理
  }
}

// 消息有变化就存（只留最近 200 条，避免 localStorage 膨胀）
// 流式回复时每个 token 都会触发 watch，防抖 500ms 合并写入；流结束由 send() 里兜底立即存
const persistMessages = () => {
  try {
    localStorage.setItem(storageKey(), JSON.stringify(messages.value.slice(-200)))
  } catch {
    // 存储满了/隐私模式等情况，静默失败不影响聊天
  }
}
let persistTimer = null
watch(
  messages,
  () => {
    clearTimeout(persistTimer)
    persistTimer = setTimeout(persistMessages, 500)
  },
  { deep: true }
)

// ===== 长期记忆：进页面时问后端"AI 记得我吗" =====
const hasMemory = ref(false) // 有档案或记忆卡 = 老朋友，欢迎语变化
const memoryLoaded = ref(false)

onMounted(async () => {
  // 先恢复本地聊天记录，避免切换页面回来后"对话瞬间消失"
  loadMessages()

  // 历史遗留的无时间戳记录：补上当前时间（视为升级后首次见到的那天），
  // 这样今天能正常按天生成报告，明天起自动按真实日期隔离
  let legacyPatched = false
  messages.value.forEach((m) => {
    if (!m.ts) {
      m.ts = Date.now()
      legacyPatched = true
    }
  })
  if (legacyPatched) {
    localStorage.setItem(storageKey(), JSON.stringify(messages.value.slice(-200)))
  }

  try {
    const view = await request('/memory')
    hasMemory.value = !!(view.profile || (view.recentCards && view.recentCards.length))
  } catch {
    // 拉不到记忆按新用户处理，不阻塞页面
  } finally {
    memoryLoaded.value = true
  }

  // 今天已有聊天记录时，进页面就评估一次完整度
  if (todayUserTurns.value > 0) {
    maybeAssess()
  }

  nextTick(scrollToBottom)
})

// 欢迎页的快捷提问：老朋友多一个"接着上次的话题聊"
const baseSuggestions = [
  '我最近总是失眠，怎么办？',
  '工作压力很大，感觉喘不过气',
  '如何缓解考试焦虑？',
  '和好朋友闹矛盾了，心里很难受',
]
const suggestions = computed(() =>
  hasMemory.value ? ['接着上次的话题聊', ...baseSuggestions] : baseSuggestions
)

// ===== 报告按天生成：只统计"今天"的聊天记录，不再看轮数 =====
// 每条消息写入时带时间戳 ts；历史遗留的无 ts 记录在 onMounted 里补上当前时间
const isToday = (ts) => {
  const d = new Date(ts)
  const n = new Date()
  return (
    d.getFullYear() === n.getFullYear() &&
    d.getMonth() === n.getMonth() &&
    d.getDate() === n.getDate()
  )
}
const todayHistory = computed(() => messages.value.filter((m) => !m.ts || isToday(m.ts)))
const todayUserTurns = computed(() => todayHistory.value.filter((m) => m.role === 'user').length)

// ===== AI 完整度评分：今天有聊天记录就让 AI 评估，达到阈值才允许生成报告 =====
// 阈值判定在后端（MIN_SCORE=70），前端 ready 只用来控制按钮状态与提示文案
const readiness = ref({ score: 0, ready: false, reason: '', missingInfo: [] })
const assessing = ref(false)

const maybeAssess = async () => {
  if (todayUserTurns.value === 0 || assessing.value) return
  assessing.value = true
  try {
    // 只评估今天的记录，与报告口径一致
    readiness.value = await request('/chat/assess', {
      method: 'POST',
      body: { history: todayHistory.value },
    })
  } catch {
    // 评估失败不打扰用户，下次回复后会重试
  } finally {
    assessing.value = false
  }
}

// 生成今日心理分析报告：把"今天"的聊天记录交给后端（模型强制输出结构化 JSON 报告并存库）
// 成功后直接弹窗展示报告，不用跳页
const generating = ref(false)
const reportVisible = ref(false) // 报告弹窗开关
const lastReport = ref(null) // 刚生成的报告对象（结构同"我的报告"页的报告）

// 严重程度 → 标签颜色（与"我的报告"页一致）
const severityType = { 轻度: 'success', 中度: 'warning', 重度: 'danger' }

// narrative 的 Markdown 转 HTML：模型输出属外部内容，必须过 DOMPurify 消毒再 v-html
const mdHtml = (text) => DOMPurify.sanitize(marked.parse(text || ''))

const genReport = async () => {
  if (todayUserTurns.value === 0 || generating.value) return
  generating.value = true
  try {
    // 只传今天的记录：报告按天生成，昨天的内容已在昨天的报告里
    lastReport.value = await request('/reports/generate', {
      method: 'POST',
      body: { history: todayHistory.value },
    })
    reportVisible.value = true
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    generating.value = false
  }
}

// ===== 结束今天的聊天：主动把当前对话沉淀成 AI 长期记忆 =====
// 与"生成报告自动记忆"的区别：随时可主动收尾，不需要报告，
// 走的是同一个后端 capture（失败只记日志不抛错），记忆规则完全一致。
const ending = ref(false)
const endToday = async () => {
  if (ending.value) return
  // 空对话没必要调模型，直接去结束页
  if (messages.value.length === 0) {
    router.push('/user/ended')
    return
  }
  try {
    await ElMessageBox.confirm(
      '结束后，AI 会把今天聊的内容整理进长期记忆，当前对话将清空。确定结束吗？',
      '结束今天的聊天',
      { type: 'info', confirmButtonText: '结束并保存记忆', cancelButtonText: '再聊一会' }
    )
  } catch {
    return // 用户取消
  }

  ending.value = true
  try {
    const history = messages.value.map((m) => ({ role: m.role, content: m.content }))
    const card = await request('/memory/capture', { method: 'POST', body: { history } })
    // capture 内部 catch-all，模型异常时 data 为 null——记忆没存上，不能假装成功
    if (!card) {
      ElMessage.error('记忆保存时出了点问题，请重试一次')
      return
    }
    // 记忆落库成功：清空当前对话与本地缓存，重置评分状态
    messages.value = []
    readiness.value = { score: 0, ready: false, reason: '', missingInfo: [] }
    localStorage.removeItem(storageKey())
    ElMessage.success('今天的记忆已保存')
    router.push('/user/ended')
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    ending.value = false
  }
}

// 消息列表自动滚到底部
const scrollToBottom = async () => {
  await nextTick()
  if (listRef.value) listRef.value.scrollTop = listRef.value.scrollHeight
}

// 三点动画只在"等待第一个字"时显示；AI 气泡一旦出现，文字直接在气泡里生长
const showTyping = computed(
  () =>
    thinking.value &&
    messages.value[messages.value.length - 1]?.role !== 'assistant'
)

// 发送消息 → SSE 流式接收：AI 边生成，气泡里边逐段出现（打字机效果）
const send = async (text) => {
  const content = (text ?? input.value).trim()
  if (!content || thinking.value) return

  messages.value.push({ role: 'user', content, ts: Date.now() })
  input.value = ''
  scrollToBottom()

  thinking.value = true
  scrollToBottom()
  // 参考来源永远比正文先到，先暂存，第一个文字增量到达时随气泡一起入列
  let pendingSources = []
  try {
    // 带上最近 10 条历史（不含刚发的这条），模型才有上下文记忆
    const history = messages.value
      .slice(0, -1)
      .slice(-10)
      .map((m) => ({ role: m.role, content: m.content }))

    await requestStream('/chat', {
      body: { message: content, history },
      onEvent: (name, data) => {
        if (name === 'sources') {
          // sources 是本次回答引用到的知识库文章（RAG）；解析失败按无来源处理，不影响正文
          try {
            pendingSources = JSON.parse(data) || []
          } catch {
            pendingSources = []
          }
          return
        }
        if (name === 'delta') {
          let last = messages.value[messages.value.length - 1]
          if (!last || last.role !== 'assistant') {
            // 第一个增量：正式把 AI 气泡推进列表
            messages.value.push({
              role: 'assistant',
              content: '',
              sources: pendingSources,
              ts: Date.now(),
            })
            last = messages.value[messages.value.length - 1]
          }
          // 必须改"数组里的代理对象"才会触发视图更新；
          // 若持有 push 前的字面量对象改属性，界面不会动
          last.content += JSON.parse(data).content
          scrollToBottom()
          return
        }
        if (name === 'error') {
          // 流式中途出错：已有半截回答就换行追加提示，否则整段错误作为气泡
          const msg = JSON.parse(data).message
          const last = messages.value[messages.value.length - 1]
          if (last && last.role === 'assistant' && last.content) {
            last.content += '\n\n（回复中断：' + msg + '）'
          } else {
            messages.value.push({
              role: 'assistant',
              content: '抱歉，我这边开小差了：' + msg,
              ts: Date.now(),
            })
          }
        }
        // done：无需处理，流自然结束，finally 统一收尾
      },
    })
  } catch (e) {
    // SSE 建立前就失败（未登录/参数校验/网络不通），出错也在对话流里显示
    messages.value.push({
      role: 'assistant',
      content: '抱歉，我这边开小差了：' + e.message,
      ts: Date.now(),
    })
  } finally {
    thinking.value = false
    scrollToBottom()
    persistMessages() // 防抖 500ms 还没落盘的增量，流结束立即存一次
    // 回复完成后刷新今天的完整度评分
    maybeAssess()
  }
}
</script>

<template>
  <div class="chat-page">
    <!-- 消息区：空状态显示欢迎语，有消息后显示气泡列表 -->
    <div ref="listRef" class="chat-list">
      <div v-if="messages.length === 0 && !thinking" class="welcome">
        <div class="welcome-logo">AI</div>
        <h2 class="welcome-title">
          {{ hasMemory ? '欢迎回来，好久没聊了' : '你好，我是你的 AI 心理助手' }}
        </h2>
        <p class="welcome-sub">
          {{ hasMemory ? '我还记得我们之前聊过的事，随时可以继续。' : '心里的事，说出来会轻一点。随时和我聊聊。' }}
        </p>
        <div class="suggestions">
          <button
            v-for="s in suggestions"
            :key="s"
            class="suggestion"
            @click="send(s)"
          >
            {{ s }}
          </button>
        </div>
      </div>

      <template v-for="(m, i) in messages" :key="i">
        <!-- 用户消息：右侧 -->
        <div v-if="m.role === 'user'" class="row row-user">
          <div class="bubble bubble-user">{{ m.content }}</div>
          <div class="avatar avatar-user">我</div>
        </div>
        <!-- 助手消息：左侧 -->
        <div v-else class="row row-ai">
          <div class="avatar avatar-ai">AI</div>
          <div class="ai-body">
            <div class="bubble bubble-ai">{{ m.content }}</div>
            <!-- RAG 参考来源：命中知识库时展示在气泡下方 -->
            <div v-if="m.sources && m.sources.length" class="sources">
              参考来源：
              <span v-for="s in m.sources" :key="s.articleId" class="source-tag">《{{ s.title }}》</span>
            </div>
          </div>
        </div>
      </template>

      <!-- 正在输入指示器：AI 气泡出现前显示三点动画，出现后直接看气泡里文字生长 -->
      <div v-if="showTyping" class="row row-ai">
        <div class="avatar avatar-ai">AI</div>
        <div class="bubble bubble-ai typing">
          <span></span><span></span><span></span>
        </div>
      </div>
    </div>

    <!-- 输入区 -->
      <div class="input-bar">
        <div class="report-bar">
          <el-button
            size="small"
            round
            :icon="Document"
            :loading="generating"
            :disabled="todayUserTurns === 0 || !readiness.ready"
            @click="genReport"
          >
            生成今日分析报告
          </el-button>
          <el-progress
            v-if="todayUserTurns > 0"
            type="circle"
            :percentage="readiness.score"
            :width="26"
            :stroke-width="3"
            :status="readiness.ready ? 'success' : undefined"
            :show-text="false"
          />
          <!-- 按天生成的状态提示：记录数 + 完整度评分 -->
          <span class="report-tip">
            <template v-if="todayUserTurns === 0">
              今天还没有聊天记录，先和 AI 聊聊吧
            </template>
            <template v-else-if="assessing">
              AI 正在评估今天对话的信息完整度…
            </template>
            <template v-else-if="!readiness.ready">
              今天 {{ todayHistory.length }} 条记录，信息完整度 {{ readiness.score }}%，{{ readiness.reason }}<template v-if="readiness.missingInfo.length">（缺：{{ readiness.missingInfo.join('、') }}）</template>
            </template>
            <template v-else>
              报告将根据今天的 {{ todayHistory.length }} 条聊天记录生成（完整度 {{ readiness.score }}%）
            </template>
          </span>
          <el-button
            size="small"
            round
            plain
            :icon="SwitchButton"
            :loading="ending"
            @click="endToday"
          >
            结束今天的聊天
          </el-button>
        </div>
      <div class="input-box">
        <input
          v-model="input"
          class="chat-input"
          placeholder="给 AI 心理助手发送消息…（Enter 发送）"
          @keyup.enter="send()"
        />
        <el-button
          type="primary"
          class="send-btn"
          :icon="Promotion"
          :disabled="!input.trim() || thinking"
          @click="send()"
        >
          发送
        </el-button>
      </div>
      <p class="input-tip">内容由 AI 生成，仅供参考，不能替代专业心理咨询</p>
    </div>

    <!-- 今日报告弹窗：生成成功后直接展示给用户 -->
    <el-dialog
      v-model="reportVisible"
      title="今日心理分析报告"
      width="640px"
      class="report-dialog"
    >
      <template v-if="lastReport">
        <!-- 报告头部：生成时间 + 严重程度 + 风险标签 + 一句话概述 -->
        <div class="rp-head">
          <el-tag :type="severityType[lastReport.severity] || 'info'" effect="dark">
            {{ lastReport.severity }}
          </el-tag>
          <el-tag v-if="lastReport.riskFlag" type="danger" effect="plain">存在风险信号</el-tag>
          <span class="rp-time">{{ lastReport.createdAt }}</span>
        </div>
        <p class="rp-summary">{{ lastReport.stateSummary }}</p>

        <!-- 判断依据 -->
        <div class="rp-block">
          <div class="rp-label">严重程度判断依据</div>
          <p class="rp-reason">{{ lastReport.severityReason }}</p>
        </div>

        <!-- 情绪标签 -->
        <div class="rp-block" v-if="lastReport.emotionTags && lastReport.emotionTags.length">
          <div class="rp-label">情绪标签</div>
          <div class="rp-tags">
            <el-tag v-for="t in lastReport.emotionTags" :key="t" type="info" effect="plain">
              {{ t }}
            </el-tag>
          </div>
        </div>

        <!-- 建议列表 -->
        <div class="rp-block" v-if="lastReport.suggestions && lastReport.suggestions.length">
          <div class="rp-label">建议与措施</div>
          <ul class="rp-suggestions">
            <li v-for="(s, i) in lastReport.suggestions" :key="i">{{ s }}</li>
          </ul>
        </div>

        <!-- 高风险警示 -->
        <el-alert
          v-if="lastReport.riskFlag"
          type="error"
          :closable="false"
          show-icon
          class="rp-risk"
          title="报告识别到高危风险信号，请尽快联系专业机构或心理援助热线"
        />

        <!-- Markdown 报告全文 -->
        <div class="rp-block">
          <div class="rp-label">报告全文</div>
          <div class="rp-markdown" v-html="mdHtml(lastReport.narrative)"></div>
        </div>
      </template>

      <template #footer>
        <el-button round @click="reportVisible = false">继续聊聊</el-button>
        <el-button type="primary" round @click="router.push('/user/reports')">
          查看全部报告
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* 整页布局：上下两段，消息区占满剩余空间 */
.chat-page {
  height: calc(100vh - 60px - 48px); /* 视口高 - 顶栏 - main 上下内边距 */
  max-width: 860px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
}
.chat-list {
  flex: 1;
  overflow-y: auto;
  padding: 12px 4px;
}

/* ===== 欢迎状态：绿标 + 大标题 + 快捷提问卡片 ===== */
.welcome {
  text-align: center;
  padding-top: 8vh;
}
.welcome-logo {
  width: 64px;
  height: 64px;
  margin: 0 auto 20px;
  border-radius: 20px;
  background: var(--brand-gradient);
  box-shadow: 0 12px 32px rgba(57, 104, 77, 0.3);
  color: #fff;
  font-weight: 700;
  font-size: 22px;
  letter-spacing: 1px;
  display: grid;
  place-items: center;
}
.welcome-title {
  margin: 0 0 10px;
  font-size: 24px;
  font-weight: 600;
  letter-spacing: 1px;
  color: var(--ink-1);
}
.welcome-sub {
  margin: 0 0 32px;
  color: var(--ink-2);
  font-size: 14px;
  letter-spacing: 0.5px;
}
.suggestions {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  max-width: 560px;
  margin: 0 auto;
}
.suggestion {
  padding: 14px 18px;
  border: 1px solid var(--line);
  border-radius: 14px;
  background: var(--bg-card);
  color: var(--ink-2);
  font-size: 14px;
  cursor: pointer;
  transition: border-color 0.2s, color 0.2s, transform 0.2s, box-shadow 0.2s;
  text-align: left;
}
.suggestion:hover {
  border-color: var(--brand);
  color: var(--brand-deep);
  transform: translateY(-2px);
  box-shadow: var(--shadow-soft);
}

/* 消息行 */
.row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin-bottom: 18px;
}
.row-user {
  justify-content: flex-end;
}
.row-ai {
  justify-content: flex-start;
}
.avatar {
  flex: 0 0 auto;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  font-size: 12px;
  color: #fff;
}
/* 用户头像用暖杏色：一片绿里的一点暖，画面不那么冷 */
.avatar-user {
  background: linear-gradient(135deg, #d9b287, #c49a6b);
}
.avatar-ai {
  background: var(--brand-gradient);
  box-shadow: 0 4px 12px rgba(57, 104, 77, 0.25);
  font-weight: 700;
}

/* 气泡：大圆角 + 细描边/浅投影，像卡片而不是色块 */
.bubble {
  max-width: 72%;
  padding: 12px 16px;
  border-radius: 16px;
  font-size: 14px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}
.bubble-user {
  background: var(--brand-gradient);
  box-shadow: 0 6px 16px rgba(81, 146, 110, 0.24);
  color: #fff;
  border-top-right-radius: 4px;
}
.bubble-ai {
  background: var(--bg-card);
  color: var(--ink-1);
  border: 1px solid var(--line);
  box-shadow: 0 2px 10px rgba(57, 104, 77, 0.06);
  border-top-left-radius: 4px;
}
/* 助手消息体（气泡 + 参考来源）：把 .bubble 的 72% 限宽接管到这一层 */
.ai-body {
  max-width: 72%;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.ai-body .bubble-ai {
  max-width: 100%;
}
.sources {
  font-size: 12px;
  color: var(--ink-3);
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
}
.source-tag {
  background: var(--brand-soft);
  color: var(--brand-deep);
  border: 1px solid #d5e5da;
  border-radius: 10px;
  padding: 1px 8px;
}

/* 正在输入的三个跳动圆点 */
.typing {
  display: inline-flex;
  gap: 5px;
  align-items: center;
  min-height: 36px;
}
.typing span {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #a9bcae;
  animation: blink 1.2s infinite;
}
.typing span:nth-child(2) {
  animation-delay: 0.2s;
}
.typing span:nth-child(3) {
  animation-delay: 0.4s;
}
@keyframes blink {
  0%, 60%, 100% { opacity: 0.3; }
  30% { opacity: 1; }
}

/* ===== 输入区：悬浮胶囊卡片，聚焦时泛出绿色光晕 ===== */
.input-bar {
  flex: 0 0 auto;
  padding: 8px 0 4px;
}
.input-box {
  display: flex;
  gap: 10px;
  align-items: center;
  background: var(--bg-card);
  border: 1px solid var(--line);
  border-radius: 18px;
  padding: 9px 12px 9px 18px;
  box-shadow: var(--shadow-soft);
  transition: border-color 0.2s, box-shadow 0.2s;
}
.input-box:focus-within {
  border-color: var(--brand);
  box-shadow: 0 0 0 4px rgba(81, 146, 110, 0.12), var(--shadow-soft);
}
.report-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
}
.report-tip {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  color: var(--ink-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.chat-input {
  flex: 1;
  border: none;
  outline: none;
  font-size: 14px;
  color: var(--ink-1);
  background: transparent;
}
.chat-input::placeholder {
  color: var(--ink-3);
}
.send-btn {
  border-radius: 12px;
}
.input-tip {
  text-align: center;
  font-size: 12px;
  color: var(--ink-3);
  margin: 8px 0 0;
}

/* ===== 今日报告弹窗 ===== */
.rp-head {
  display: flex;
  align-items: center;
  gap: 10px;
}
.rp-time {
  font-size: 13px;
  color: var(--ink-3);
}
.rp-summary {
  margin: 12px 0 0;
  font-size: 14px;
  color: var(--ink-1);
  line-height: 1.7;
}
.rp-block {
  margin-top: 16px;
}
.rp-label {
  font-size: 13px;
  color: var(--ink-3);
  margin-bottom: 6px;
}
.rp-reason {
  margin: 0;
  font-size: 14px;
  color: var(--ink-1);
  line-height: 1.7;
}
.rp-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.rp-suggestions {
  margin: 0;
  padding-left: 20px;
  font-size: 14px;
  color: var(--ink-1);
  line-height: 1.9;
}
.rp-risk {
  margin-top: 16px;
}

/* Markdown 正文（v-html 内容需用 :deep 穿透 scoped，与"我的报告"页同款） */
.rp-markdown {
  font-size: 14px;
  color: var(--ink-1);
  line-height: 1.8;
  max-height: 320px;
  overflow-y: auto;
  padding-right: 6px;
}
.rp-markdown :deep(h1),
.rp-markdown :deep(h2),
.rp-markdown :deep(h3) {
  font-size: 16px;
  margin: 14px 0 8px;
  color: var(--ink-1);
}
.rp-markdown :deep(p) {
  margin: 8px 0;
}
.rp-markdown :deep(ul),
.rp-markdown :deep(ol) {
  padding-left: 20px;
  margin: 8px 0;
}
.rp-markdown :deep(code) {
  background: var(--brand-soft);
  color: var(--brand-deep);
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 13px;
}
.rp-markdown :deep(blockquote) {
  margin: 8px 0;
  padding: 4px 12px;
  border-left: 3px solid var(--brand);
  color: var(--ink-2);
}
</style>

<script setup>
// ===== 故事集 · 心声墙：全屏飘落的文字弹幕 =====
// 实现要点（五条）：
// 1. 列调度：远/中/近三层各自分列，同列前一条落出底部后下一条才进场，层内绝不重叠
// 2. 纵深感：三层独立列池且允许互相重叠——远小快半透模糊，近大慢清晰
// 3. 柔和进出：页面上下边缘渐隐遮罩（::before / ::after）
// 4. 自然感：每条随机倾斜角、水平漂移、下落速度、列内偏移
// 5. 细节：爱心放大回弹变色、背景缓慢明暗呼吸、三层错开 2 秒依次入场
import { ref, reactive, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft, EditPen } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { request } from '@/api/request'

const router = useRouter()
const rootEl = ref(null)

// ===== 发布心声弹窗状态 =====
const dialogVisible = ref(false)
const submitting = ref(false)
const form = reactive({ username: '', content: '' })

// 三层配置：越近越大越清晰越慢；层间 z-index 不同，允许互相穿越重叠
const LAYERS = [
  { key: 'far', cardW: 216, gap: 20, fontSize: 12, opacity: 0.38, blur: 2.5, scale: 0.88,
    z: 1, dur: [11, 15], spawn: [1300, 2400] },
  { key: 'mid', cardW: 258, gap: 26, fontSize: 14, opacity: 0.72, blur: 1, scale: 1,
    z: 2, dur: [17, 23], spawn: [1900, 3100] },
  { key: 'near', cardW: 300, gap: 32, fontSize: 16, opacity: 1, blur: 0, scale: 1.05,
    z: 3, dur: [25, 33], spawn: [2500, 4000] },
]

const items = ref([])        // 屏幕上所有正在飘落的卡片
const layerIn = reactive({ far: false, mid: false, near: false }) // 三层错峰入场
let pool = []                // 心声数据池（后端拉取）
let uidSeq = 0
let lastMsgId = null
const timers = []
let observer = null

// 每层自己的列状态：busy[laneIndex] = 占用者的 uid（null = 空闲）
const laneState = {}
LAYERS.forEach((l) => (laneState[l.key] = { busy: [] }))

const rand = (min, max) => min + Math.random() * (max - min)
const laneCountFor = (cfg, w) =>
  Math.max(1, Math.floor((w + cfg.gap) / (cfg.cardW + cfg.gap)))

// 根据当前容器宽度（重新）计算各层列数；resize 时已在落的卡片继续落完
const rebuildLanes = () => {
  const w = rootEl.value?.clientWidth || window.innerWidth
  LAYERS.forEach((cfg) => {
    const st = laneState[cfg.key]
    const n = laneCountFor(cfg, w)
    const next = new Array(n).fill(null)
    st.busy.forEach((u, i) => {
      if (u && i < n) next[i] = u // 旧列还在就保留占用
    })
    st.busy = next
  })
}

// 随机取一条心声，避免和上一条完全重复
const pickMsg = () => {
  if (pool.length === 0) return null
  if (pool.length === 1) return pool[0]
  let m
  do {
    m = pool[Math.floor(Math.random() * pool.length)]
  } while (m.id === lastMsgId)
  lastMsgId = m.id
  return m
}

// 单层调度循环：随机间隔尝试在空闲列生成一条
const scheduleNext = (cfg) => {
  const t = setTimeout(() => spawn(cfg), rand(cfg.spawn[0], cfg.spawn[1]))
  timers.push(t)
}

const spawn = (cfg) => {
  const st = laneState[cfg.key]
  const free = []
  st.busy.forEach((u, i) => {
    if (!u) free.push(i)
  })
  // 所有列都忙就跳过这一轮，等下一个周期（防重叠的根本保证）
  if (free.length === 0 || !rootEl.value) {
    scheduleNext(cfg)
    return
  }
  const msg = pickMsg()
  if (!msg) {
    scheduleNext(cfg)
    return
  }

  const lane = free[Math.floor(Math.random() * free.length)]
  const uid = ++uidSeq
  const jitter = rand(-10, 10) // 列内轻微水平偏移，避免整齐划一
  const left = Math.max(0, Math.min(
    lane * (cfg.cardW + cfg.gap) + jitter,
    rootEl.value.clientWidth - cfg.cardW - 4
  ))

  items.value.push({
    uid,
    lane,
    layer: cfg.key,
    left,
    width: cfg.cardW,
    z: cfg.z,
    fontSize: cfg.fontSize,
    opacity: cfg.opacity,
    blur: cfg.blur,
    scale: cfg.scale,
    duration: rand(cfg.dur[0], cfg.dur[1]).toFixed(2),
    rot0: rand(-5, 5).toFixed(2),
    rot1: rand(-6, 6).toFixed(2),
    drift: rand(-16, 16).toFixed(0),
    msg,
    likes: msg.likes,
    liked: msg.liked,
    liking: false,
    pop: false,
  })
  st.busy[lane] = uid
  scheduleNext(cfg)
}

// 卡片落出屏幕：释放所在列并移除（仅当该列占用者还是自己）
const onFallEnd = (item) => {
  const st = laneState[item.layer]
  if (st.busy[item.lane] === item.uid) {
    st.busy[item.lane] = null
  }
  items.value = items.value.filter((i) => i.uid !== item.uid)
}

// 点赞：乐观更新 + 爱心回弹；后端用 Redis SADD+HINCRBY 防重，失败回滚
const like = async (item) => {
  if (item.liking || item.liked) return
  item.liking = true
  item.likes += 1
  item.liked = true
  item.pop = true
  setTimeout(() => (item.pop = false), 450)
  try {
    item.likes = await request(`/wall/like/${item.msg.id}`, { method: 'POST' })
  } catch {
    item.likes -= 1
    item.liked = false
  } finally {
    item.liking = false
  }
}

// ===== 发布心声：真实写入 Redis，成功后进数据池，稍后会自然飘落出现 =====
const openPublish = () => {
  dialogVisible.value = true
}

const submitPublish = async () => {
  if (!form.content.trim()) {
    ElMessage.warning('内容不能为空')
    return
  }
  submitting.value = true
  try {
    const created = await request('/wall', {
      method: 'POST',
      body: { username: form.username.trim(), content: form.content.trim() },
    })
    pool.unshift(created)
    dialogVisible.value = false
    form.username = ''
    form.content = ''
    ElMessage.success('发布成功，你的心声已经飘进故事集')
  } catch (e) {
    // 后端 30 秒冷却等提示语会直接显示给用户
    ElMessage.error(e.message)
  } finally {
    submitting.value = false
  }
}

// ===== 删除自己发布的心声：二次确认后调真实接口，同步清掉数据池和屏幕上的卡片 =====
const deleteOwn = async (item) => {
  try {
    await ElMessageBox.confirm('确定删除这条心声吗？删除后不可恢复。', '删除心声', {
      confirmButtonText: '删除',
      cancelButtonText: '再想想',
      type: 'warning',
    })
  } catch {
    return // 用户点了取消
  }
  try {
    await request(`/wall/${item.msg.id}`, { method: 'DELETE' })
    pool = pool.filter((m) => m.id !== item.msg.id)
    // 同一条心声可能正在多个层/列上飘落，一并移除并释放所占列
    const gone = items.value.filter((i) => i.msg.id === item.msg.id)
    gone.forEach((i) => {
      const st = laneState[i.layer]
      if (st.busy[i.lane] === i.uid) st.busy[i.lane] = null
    })
    items.value = items.value.filter((i) => i.msg.id !== item.msg.id)
    ElMessage.success('已删除')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

onMounted(async () => {
  try {
    pool = await request('/wall')
  } catch {
    pool = []
  }
  rebuildLanes()

  // 三层错开 2 秒依次入场：淡入 + 开始生成飘落卡片
  LAYERS.forEach((cfg, idx) => {
    const t = setTimeout(() => {
      layerIn[cfg.key] = true
      spawn(cfg)
    }, idx * 2000)
    timers.push(t)
  })

  observer = new ResizeObserver(rebuildLanes)
  observer.observe(rootEl.value)
})

onBeforeUnmount(() => {
  timers.forEach(clearTimeout)
  observer?.disconnect()
})
</script>

<template>
  <!-- fixed 全屏覆盖，包含顶栏，沉浸式；返回靠左上角悬浮按钮 -->
  <div ref="rootEl" class="wall-page">
    <!-- 极缓慢明暗呼吸的背景层 -->
    <div class="bg-breathe"></div>

    <!-- 远 / 中 / 近 三层，各自独立列池 -->
    <div
      v-for="cfg in LAYERS"
      :key="cfg.key"
      class="layer"
      :class="[cfg.key, { in: layerIn[cfg.key] }]"
    >
      <div
        v-for="item in items.filter((i) => i.layer === cfg.key)"
        :key="item.uid"
        class="voice-card"
        :style="{
          left: item.left + 'px',
          width: item.width + 'px',
          zIndex: item.z,
          opacity: item.opacity,
          fontSize: item.fontSize + 'px',
          filter: item.blur ? `blur(${item.blur}px)` : 'none',
          '--scale': item.scale,
          '--dur': item.duration + 's',
          '--rot0': item.rot0 + 'deg',
          '--rot1': item.rot1 + 'deg',
          '--drift': item.drift + 'px',
        }"
        @animationend="onFallEnd(item)"
      >
        <div class="v-user">{{ item.msg.username }}</div>
        <div class="v-text">{{ item.msg.content }}</div>
        <button
          class="v-like"
          :class="{ liked: item.liked, pop: item.pop }"
          @click.stop="like(item)"
        >
          <svg viewBox="0 0 24 24" width="15" height="15" aria-hidden="true">
            <path
              d="M12 21s-7.5-4.9-10-9.2C.4 8.6 2 5 5.5 5c2 0 3.4 1.1 4.5 2.6C11.1 6.1 12.5 5 14.5 5 18 5 19.6 8.6 22 11.8 19.5 16.1 12 21 12 21z"
              fill="currentColor"
              stroke="currentColor"
              stroke-width="1.2"
            />
          </svg>
          <span>{{ item.likes }}</span>
        </button>
        <!-- 仅自己发布的心声显示删除入口 -->
        <button v-if="item.msg.owner" class="v-del" @click.stop="deleteOwn(item)">
          删除
        </button>
      </div>
    </div>

    <!-- 顶部悬浮 UI -->
    <div class="wall-ui">
      <button class="back-btn" @click="router.push('/user/home')">
        <el-icon><ArrowLeft /></el-icon>
        返回
      </button>
      <div class="wall-title">
        <div class="t1">故事集</div>
        <div class="t2">这里有很多人，他们也和你一样</div>
      </div>
      <button class="write-btn" @click="openPublish">
        <el-icon><EditPen /></el-icon>
        写下心声
      </button>
    </div>

    <!-- 发布心声弹窗：内容真实入库，30 秒防刷由后端守门 -->
    <el-dialog
      v-model="dialogVisible"
      title="写下心声"
      width="460px"
      class="publish-dialog"
    >
      <el-input
        v-model="form.username"
        maxlength="30"
        placeholder="昵称（留空则显示为「匿名」）"
        class="pub-field"
      />
      <el-input
        v-model="form.content"
        type="textarea"
        :rows="5"
        maxlength="200"
        show-word-limit
        placeholder="写下此刻想说的一句话，它会飘进故事集里……"
        class="pub-field"
      />
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitPublish">
          发布
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* ===== 全屏容器：深夜森林绿，比蓝紫色更安静 ===== */
.wall-page {
  position: fixed;
  inset: 0;
  z-index: 2000;
  overflow: hidden;
  background: linear-gradient(180deg, #0a1712 0%, #0f231a 55%, #133122 100%);
  font-family: 'PingFang SC', 'Microsoft YaHei', sans-serif;
}

/* 上下边缘渐隐遮罩：文字从雾里飘来、飘回雾里 */
.wall-page::before,
.wall-page::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  height: 150px;
  z-index: 6;
  pointer-events: none;
}
.wall-page::before {
  top: 0;
  background: linear-gradient(180deg, rgba(10, 23, 18, 0.95), rgba(10, 23, 18, 0));
}
.wall-page::after {
  bottom: 0;
  background: linear-gradient(0deg, rgba(10, 23, 18, 0.95), rgba(10, 23, 18, 0));
}

/* ===== 背景缓慢明暗呼吸：林间绿光 + 一点篝火的暖 ===== */
.bg-breathe {
  position: absolute;
  inset: -10%;
  background:
    radial-gradient(40% 35% at 20% 25%, rgba(94, 166, 126, 0.18), transparent 70%),
    radial-gradient(45% 40% at 80% 70%, rgba(224, 183, 135, 0.1), transparent 70%);
  animation: breathe 9s ease-in-out infinite alternate;
}
@keyframes breathe {
  from { opacity: 0.5; transform: scale(1.02); }
  to   { opacity: 1;   transform: scale(1.12); }
}

/* ===== 三层：错峰 2 秒淡入 ===== */
.layer {
  position: absolute;
  inset: 0;
  opacity: 0;
  transition: opacity 1.6s ease;
  will-change: opacity;
}
.layer.in {
  opacity: 1;
}

/* ===== 飘落卡片：每条独立动画（单一驱动源）===== */
.voice-card {
  position: absolute;
  top: 0;
  padding: 14px 16px 10px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.07);
  border: 1px solid rgba(255, 255, 255, 0.12);
  color: #eaf4ec;
  line-height: 1.7;
  box-shadow: 0 8px 30px rgba(0, 0, 0, 0.25);
  transform: scale(var(--scale));
  transform-origin: center top;
  animation-name: fall;
  animation-timing-function: linear;
  animation-fill-mode: forwards;
  animation-duration: var(--dur);
  will-change: transform;
  /* 卡片本身不吃鼠标，只有爱心按钮可点，避免遮挡下层 */
  pointer-events: none;
}
@keyframes fall {
  0% {
    transform: translate3d(0, -380px, 0) rotate(var(--rot0)) scale(var(--scale));
  }
  100% {
    transform: translate3d(var(--drift), 108vh, 0) rotate(var(--rot1)) scale(var(--scale));
  }
}

.v-user {
  font-size: 0.82em;
  color: rgba(170, 214, 188, 0.85);
  margin-bottom: 6px;
  letter-spacing: 0.5px;
}
.v-text {
  font-size: 1em;
  word-break: break-word;
}

/* 近景层卡片更清晰、更有质感 */
.layer.near .voice-card {
  background: rgba(255, 255, 255, 0.1);
  border-color: rgba(255, 255, 255, 0.2);
}

/* ===== 爱心点赞 ===== */
.v-like {
  pointer-events: auto;
  margin-top: 10px;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border: none;
  background: transparent;
  color: rgba(255, 255, 255, 0.45);
  font-size: 0.8em;
  cursor: pointer;
  padding: 2px 0;
  transition: color 0.2s, transform 0.2s;
}
.v-like:hover {
  color: rgba(255, 120, 150, 0.85);
}
.v-like.liked {
  color: #ff6b8b;
}
/* 点击瞬间放大回弹 */
.v-like.pop {
  animation: heart-pop 0.45s cubic-bezier(0.34, 1.56, 0.64, 1);
}
@keyframes heart-pop {
  0%   { transform: scale(1); }
  35%  { transform: scale(1.55); }
  60%  { transform: scale(0.85); }
  100% { transform: scale(1); }
}

/* ===== 顶部悬浮 UI ===== */
.wall-ui {
  position: absolute;
  top: 22px;
  left: 26px;
  right: 26px;
  z-index: 10;
  display: flex;
  align-items: center;
  gap: 18px;
  pointer-events: none;
}
.back-btn {
  pointer-events: auto;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 8px 16px;
  border-radius: 20px;
  border: 1px solid rgba(255, 255, 255, 0.25);
  background: rgba(255, 255, 255, 0.08);
  color: #eaf4ec;
  font-size: 13px;
  cursor: pointer;
  backdrop-filter: blur(6px);
  transition: background 0.2s;
}
.back-btn:hover {
  background: rgba(255, 255, 255, 0.18);
}
.wall-title .t1 {
  font-size: 17px;
  font-weight: 600;
  color: #f0f7f1;
  letter-spacing: 2px;
}
.wall-title .t2 {
  font-size: 12px;
  color: rgba(190, 220, 200, 0.6);
  margin-top: 2px;
}

/* 右上角「写下心声」按钮，顶到悬浮栏最右 */
.write-btn {
  pointer-events: auto;
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 18px;
  border-radius: 20px;
  border: none;
  background: linear-gradient(135deg, #63a47f, #39684d);
  color: #fff;
  font-size: 13px;
  cursor: pointer;
  backdrop-filter: blur(6px);
  box-shadow: 0 6px 18px rgba(94, 166, 126, 0.35);
  transition: transform 0.2s, box-shadow 0.2s;
}
.write-btn:hover {
  transform: translateY(-1px);
  box-shadow: 0 8px 22px rgba(94, 166, 126, 0.5);
}

/* 卡片上的删除入口：低调的小字按钮，默认半隐 */
.v-del {
  pointer-events: auto;
  margin-top: 10px;
  margin-left: 10px;
  border: none;
  background: transparent;
  color: rgba(255, 255, 255, 0.35);
  font-size: 0.75em;
  cursor: pointer;
  padding: 2px 0;
  transition: color 0.2s;
}
.v-del:hover {
  color: #ff8080;
}

/* 弹窗内两个输入框的间距（颜色在下方非 scoped 块里适配暗色） */
.pub-field {
  margin-bottom: 14px;
}
</style>

/* el-dialog 默认传送至 body，scoped 选择器够不到，单独用全局样式做暗色适配 */
<style>
.publish-dialog.el-dialog {
  background: #101f18;
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 16px;
}
.publish-dialog .el-dialog__title {
  color: #eaf4ec;
  letter-spacing: 1px;
}
.publish-dialog .el-dialog__headerbtn .el-dialog__close {
  color: rgba(255, 255, 255, 0.6);
}
.publish-dialog .el-dialog__body {
  color: #eaf4ec;
}
.publish-dialog .el-input__wrapper {
  background: rgba(255, 255, 255, 0.08);
  box-shadow: 0 0 0 1px rgba(255, 255, 255, 0.15) inset;
}
.publish-dialog .el-textarea__inner {
  background: rgba(255, 255, 255, 0.08);
  box-shadow: 0 0 0 1px rgba(255, 255, 255, 0.15) inset;
  color: #eaf4ec;
}
.publish-dialog .el-input__inner {
  color: #eaf4ec;
}
.publish-dialog .el-input__inner::placeholder,
.publish-dialog .el-textarea__inner::placeholder {
  color: rgba(190, 220, 200, 0.45);
}
.publish-dialog .el-input__count {
  background: transparent;
  color: rgba(190, 220, 200, 0.55);
}
</style>

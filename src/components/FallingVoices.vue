<script setup>
// ===== 心声飘落背景层：故事集同款三层飘落动画，纯展示不可交互 =====
// 给其他页面当沉浸式背景用（目前是首页）；数据直接拉 /api/wall，拉不到就只剩渐变底
import { ref, reactive, onMounted, onBeforeUnmount } from 'vue'
import { request } from '@/api/request'

const rootEl = ref(null)

// 三层配置：越近越大越清晰越慢（与故事集一致，保证两处观感统一）
const LAYERS = [
  { key: 'far', cardW: 216, gap: 20, fontSize: 12, opacity: 0.38, blur: 2.5, scale: 0.88,
    z: 1, dur: [11, 15], spawn: [1300, 2400] },
  { key: 'mid', cardW: 258, gap: 26, fontSize: 14, opacity: 0.72, blur: 1, scale: 1,
    z: 2, dur: [17, 23], spawn: [1900, 3100] },
  { key: 'near', cardW: 300, gap: 32, fontSize: 16, opacity: 1, blur: 0, scale: 1.05,
    z: 3, dur: [25, 33], spawn: [2500, 4000] },
]

const items = ref([])        // 正在飘落的卡片
const layerIn = reactive({ far: false, mid: false, near: false }) // 三层错峰入场
let pool = []                // 心声数据池
let uidSeq = 0
let lastMsgId = null
const timers = []
let observer = null

// 每层自己的列状态：busy[laneIndex] = 占用者 uid（null = 空闲）
const laneState = {}
LAYERS.forEach((l) => (laneState[l.key] = { busy: [] }))

const rand = (min, max) => min + Math.random() * (max - min)
const laneCountFor = (cfg, w) =>
  Math.max(1, Math.floor((w + cfg.gap) / (cfg.cardW + cfg.gap)))

const rebuildLanes = () => {
  const w = rootEl.value?.clientWidth || window.innerWidth
  LAYERS.forEach((cfg) => {
    const st = laneState[cfg.key]
    const n = laneCountFor(cfg, w)
    const next = new Array(n).fill(null)
    st.busy.forEach((u, i) => {
      if (u && i < n) next[i] = u
    })
    st.busy = next
  })
}

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
  const jitter = rand(-10, 10)
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
  })
  st.busy[lane] = uid
  scheduleNext(cfg)
}

const onFallEnd = (item) => {
  const st = laneState[item.layer]
  if (st.busy[item.lane] === item.uid) {
    st.busy[item.lane] = null
  }
  items.value = items.value.filter((i) => i.uid !== item.uid)
}

onMounted(async () => {
  try {
    pool = await request('/wall')
  } catch {
    pool = []
  }
  rebuildLanes()
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
  <!-- absolute 铺满父容器（父容器需 position:relative + overflow:hidden） -->
  <div ref="rootEl" class="voices-bg">
    <div class="bg-breathe"></div>

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
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 深夜森林绿底：与故事集同源 */
.voices-bg {
  position: absolute;
  inset: 0;
  overflow: hidden;
  background: linear-gradient(180deg, #0a1712 0%, #0f231a 55%, #133122 100%);
  pointer-events: none; /* 背景层整体不吃鼠标，交互留给上层内容 */
}

/* 上下边缘渐隐遮罩：文字从雾里飘来、飘回雾里 */
.voices-bg::before,
.voices-bg::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  height: 150px;
  z-index: 6;
}
.voices-bg::before {
  top: 0;
  background: linear-gradient(180deg, rgba(10, 23, 18, 0.95), rgba(10, 23, 18, 0));
}
.voices-bg::after {
  bottom: 0;
  background: linear-gradient(0deg, rgba(10, 23, 18, 0.95), rgba(10, 23, 18, 0));
}

/* 背景缓慢明暗呼吸：林间绿光 + 一点篝火的暖 */
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

/* 三层：错峰 2 秒淡入 */
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

/* 飘落卡片：与故事集同款玻璃质感 */
.voice-card {
  position: absolute;
  top: 0;
  padding: 14px 16px 12px;
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
</style>

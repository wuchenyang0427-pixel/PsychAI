// ===== 统一请求封装 =====
// 所有接口都通过它发送：自动拼地址、带 token、解包统一响应结构。

const SESSION_KEY = 'app_user'

// 从本地会话取登录令牌
function getToken() {
  try {
    return JSON.parse(localStorage.getItem(SESSION_KEY) || '{}').token || ''
  } catch {
    return ''
  }
}

/**
 * 发请求
 * @param {string} path 接口路径，如 /auth/login
 * @param {object} options { method, body }
 * @returns 响应里的 data 字段；业务失败时抛出带后端提示语的 Error
 */
export async function request(path, { method = 'GET', body } = {}) {
  // FormData（文件上传）：不能手动设 Content-Type，浏览器要自动带上 multipart 边界串
  const isForm = body instanceof FormData
  const res = await fetch(`/api${path}`, {
    method,
    headers: {
      // 有 token 就带上，格式：Authorization: Bearer xxx
      ...(getToken() ? { Authorization: `Bearer ${getToken()}` } : {}),
      // 普通 JSON 请求才声明 Content-Type；FormData 交给浏览器处理
      ...(isForm ? {} : { 'Content-Type': 'application/json' }),
    },
    body: body ? (isForm ? body : JSON.stringify(body)) : undefined,
  })

  const json = await res.json().catch(() => null)
  if (!res.ok || !json || json.code !== 0) {
    throw new Error(json?.message || `请求失败（HTTP ${res.status}）`)
  }
  return json.data
}

/**
 * SSE 流式请求（AI 聊天专用）：模型边生成边推，前端边收边显示。
 * @param {string} path 接口路径，如 /chat
 * @param {object} options.body 请求体（同普通 request）
 * @param {function} options.onEvent 事件回调：onEvent(name, data)，
 *   name ∈ sources（参考来源）/ delta（文字增量）/ done（结束）/ error（出错），
 *   data 是 SSE 帧里的原始字符串（JSON 内容需自行 JSON.parse）
 */
export async function requestStream(path, { body, onEvent } = {}) {
  const res = await fetch(`/api${path}`, {
    method: 'POST',
    headers: {
      ...(getToken() ? { Authorization: `Bearer ${getToken()}` } : {}),
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(body),
  })

  // 鉴权失败/参数校验失败时，SSE 还没建立，后端返回的是统一 JSON 错误——按普通请求处理
  const contentType = res.headers.get('content-type') || ''
  if (!contentType.includes('text/event-stream')) {
    const json = await res.json().catch(() => null)
    throw new Error(json?.message || `请求失败（HTTP ${res.status}）`)
  }

  // 手动解析 SSE 帧：事件之间用空行分隔，帧内每行是 "event: xxx" 或 "data: xxx"
  const reader = res.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = '' // 一个事件可能被拆到两个 TCP 包里，必须累积拼接再切
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    let m
    // 分隔符兼容 LF 与 CRLF 两种换行
    while ((m = buffer.match(/\r?\n\r?\n/))) {
      const rawEvent = buffer.slice(0, m.index)
      buffer = buffer.slice(m.index + m[0].length)
      let name = 'message'
      const dataLines = []
      for (const line of rawEvent.split(/\r?\n/)) {
        if (line.startsWith('event:')) name = line.slice(6).trim()
        else if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, ''))
      }
      if (dataLines.length) onEvent(name, dataLines.join('\n'))
    }
  }
}

# AI 聊天回答 SSE 流式输出改造计划

## Context

当前聊天链路是"一问一答整段返回"：用户发消息后，前端转圈等待后端一次性拿完 DeepSeek 完整回复（思考模型 + RAG，常需 5~30 秒），体验生硬。目标：改为 SSE 流式输出，AI 回答逐段出现在气泡里（打字机效果），感知速度显著提升。

约束：后端是 Spring MVC servlet 栈（无 WebFlux），DeepSeek 调用用 JDK HttpClient；前端用原生 fetch（EventSource 只支持 GET 且不能带请求头，不可用）；鉴权失败等前置异常仍走 GlobalExceptionHandler 的 JSON 通道（HTTP 200 + `{code,message}`），前端按 Content-Type 区分。

## SSE 事件协议（后端 → 前端）

| event | data | 时机 |
|---|---|---|
| `sources` | JSON 数组（RAG 参考来源，可空） | 检索完成后、流式开始前，第一个发 |
| `delta` | `{"content": "..."}` JSON（**必须 JSON**，content 常含 `\n`，发原始字符串会破坏 SSE 帧） | 每个模型增量 |
| `done` | 空 | 正常结束，随后 complete |
| `error` | `{"message": "友好文案"}` | 出错时发，发完直接 complete（不再发 done，前端收尾幂等） |

## 改动清单

### 1. `backend/.../service/DeepSeekService.java`
- 抽公共逻辑：现有 `chat()` 中"风险扫描 → RAG 检索 → sources → 组装 messages"抽为 `private PreparedChat prepareChat(...)`（新私有 record，含 messages + sources）。
- 新增字段 `private final ExecutorService streamPool = Executors.newCachedThreadPool();`
- 新增 `chatStream(userId, message, history, SseEmitter emitter)`：`streamPool.execute(...)` 异步执行，**主体整段 try-catch**（execute 不吞异常会致 emitter 永不 complete）：
  1. prepareChat → 发 `sources` 事件；
  2. 请求体加 `"stream": true`，`HttpRequest.timeout(60s)`（只管首包；流中段不受此超时约束，靠 emitter 超时兜底）；
  3. `BodyHandlers.ofInputStream()` + `BufferedReader.readLine()` 逐行读（InputStream 存入 `AtomicReference`，`emitter.onTimeout` 里 close 它以打断挂死的读循环；`ofLines()` 做不到这点，不用）；
  4. 解析 `data:` 行：跳过空行/注释；`[DONE]` 跳出；JSON 取 `choices[0].delta.content`（`reasoning_content` 天然忽略；首 chunk 只有 role、末 chunk 只有 finish_reason，content 缺失/null/空串一律跳过）；非空增量发 `delta` 事件（`Map.of("content", chunk)` + `MediaType.APPLICATION_JSON`）；
  5. 循环结束发 `done` → `complete()`；
  6. catch IOException（多为前端断开）→ 安静 complete；catch 其他 → 发 `error` 事件 → complete。
- 删除旧 `chat()` 方法（唯一调用方是 ChatController，同批改）。

### 2. `backend/.../controller/ChatController.java`
- `POST /api/chat` 改为返回 `SseEmitter`：**先鉴权**（失败仍走 JSON 异常通道）→ `new SseEmitter(180_000L)` → 调 `chatStream` → return。**不加 `produces`**（返回 SseEmitter 时 Spring 自动设 Content-Type）。
- `/assess` 不动。

### 3. `backend/.../dto/ChatReply.java` — 删除（前端切走后成死代码，已 grep 确认无其他引用）。

### 4. `src/api/request.js` — 新增 `requestStream(path, { body, onEvent })`（不动现有 `request`）：
- fetch POST 带 Authorization + JSON body；
- **先查 `Content-Type` 是否含 `text/event-stream`**，否则按 JSON 解析 message 并 throw（覆盖鉴权/参数校验失败）；
- `res.body.getReader()` + `TextDecoder` 流式读；buffer 累积防跨 TCP 包；事件分隔符用正则 `/\r?\n\r?\n/`（兼容 CRLF），块内按行解析 `event:` / `data:`，回调 `onEvent(name, data)`。

### 5. `src/views/user/AiAssistant.vue`
- `send()` 改用 `requestStream('/chat', ...)`：
  - `sources` → 暂存 pendingSources；
  - 首个 `delta` → `messages.value.push({ role:'assistant', content:'', sources: pendingSources, ts })`；之后**必须从数组取代理对象**追加（`messages.value[messages.value.length-1].content += chunk`，闭包持有 push 前原对象不触发响应式）；
  - `error` → 错误文案作为 assistant 气泡（已有流式气泡则追加）；`done`/`error` 置 finished；
  - `finally` 与现状一致：thinking=false + scrollToBottom + maybeAssess()。
- 三点动画条件改为 computed：`thinking && 最后一条消息不是 assistant`（流式中气泡已出现就隐藏圆点）。
- localStorage watch 持久化改 debounce 500ms + 流结束时立即存一次（避免每 token 全量写几百 KB 掉帧）。

## 不做的事
- 报告生成、assess、memory/capture 等其他接口保持非流式；
- Vite 配置零改动（dev proxy 默认流式转发）；
- 气泡仍纯文本渲染（不引入 marked 逐 token 重解析）。

## 验证
1. `java -jar target/psych-backend-0.0.1.jar`（需重新 `mvn package`）+ `npm run dev` 启动后，登录 user/123456 发消息：回答逐段出现、sources 正常显示、结束后可继续发；
2. 清除 localStorage 的 app_user 后发消息 → 出现 JSON 错误气泡而非卡死；
3. 发空消息 → 参数校验错误气泡；
4. 流式中途切换路由/刷新 → 后端无异常风暴；
5. 长回答正常结束（done），不提前截断。

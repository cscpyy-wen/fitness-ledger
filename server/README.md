# 照片营养识别代理

这个代理把 App 上传的去 EXIF JPEG 转发给支持图片输入的 OpenAI-compatible Chat Completions 服务，并把模型输出约束为**待用户核对、可编辑的营养草稿**。单张照片不会被宣称为精确称重，服务端仍强制 C/D 证据等级并保留烹调油、酱汁和隐藏配料风险。

## 部署边界

限流、UTC 日额度、provider 并发租约、失败鉴权来源和幂等结果都保存在 `STATE_DB_PATH` 指向的 SQLite WAL 数据库。每个决定使用原子写事务，所以同一台机器上共享同一数据库文件的多个 Uvicorn worker 可以共同执行一套限制，进程重启也不会清零。

隐私边界必须单独理解：数据库不保存上传的 JPEG 原图，但默认会把一次识别的**完整规范化响应**（食物名称、估算克重、营养素、风险标记以及错误响应）以 SQLite 普通字段保留 7 天，用于幂等重放。SQLite 文件本身没有应用层加密。生产部署应对状态卷启用可信的静态加密/主机全盘加密并限制备份访问；按实际重试窗口缩短 `IDEMPOTENCY_RETENTION_SECONDS`，或设为 `0` 关闭终态响应留存。设为 `0` 后仍保留运行中租约协调，但完成时会在同一事务内删除运行中记录，终态响应不会写入协调库；同一 key 后续重试会重新调用上游。宿主墙钟发生回拨时，落在当前时间之后的已完成响应也会立即删除，避免异常时钟把披露的保留期无限拉长；相应请求会重新执行，而不会回放旧响应。

这个实现的明确边界是：

- 只支持**单台宿主机 + 本地文件系统上的同一 Docker 卷**；SQLite WAL 不能放在 NFS/SMB，也不能让多台宿主机各自挂载/复制。
- `compose.example.yaml` 默认启动两个 worker，共享 `meal-analyzer-state` 本地卷。不要把服务横向扩到另一台主机。
- 需要多主机高可用时，应先把同样的事务语义迁移到受支持的 PostgreSQL/Redis 协调后端，不能用文件同步代替数据库一致性。
- provider 端仍应设置不可绕过的账户消费硬预算。

## 必需配置

密钥可以直接通过变量提供（适合本机临时开发），生产建议只用相应的 `*_FILE`：

- `PROXY_ACCESS_TOKEN` 或 `PROXY_ACCESS_TOKEN_FILE`：App 访问代理的独立长随机令牌。必须是 43–256 字节 ASCII Bearer 字符且至少有 12 个不同字符；不能与上游 API Key 相同。可用 `python -c "import secrets; print(secrets.token_urlsafe(32))"` 生成。
- `VISION_API_KEY` 或 `VISION_API_KEY_FILE`：只存在服务器上的 provider 密钥，绝不能写进 APK。
- `VISION_API_BASE_URL`：兼容服务 API 根地址，通常以 `/v1` 结尾；生产强制 HTTPS。
- `VISION_MODEL`：支持图片输入的模型名称。
- `STATE_DB_PATH`：持久化协调数据库，默认 `./data/meal-analyzer-state.db`；容器样例固定为 `/var/lib/meal-analyzer/state.db`。

如果同一密钥同时设置值变量和 `*_FILE`，服务会拒绝就绪。secret 文件最大 4 KiB，允许末尾换行。

## 防护配置

- `MAX_REQUEST_BODY_BYTES`：完整 JSON 上限，默认 `12500000`；同时覆盖有/无 `Content-Length`。
- `REQUEST_RATE_LIMIT` / `REQUEST_RATE_WINDOW_SECONDS`：按 token 指纹和客户端来源共同执行的持久滑动窗口，默认每 60 秒 10 次。
- `FORWARDED_ALLOW_IPS`：Uvicorn 可以信任转发头的直接 TCP 代理 IP/CIDR；禁止 `*`。未设置时仅 `127.0.0.1`。
- `AUTH_FAILURE_LIMIT` / `AUTH_FAILURE_WINDOW_SECONDS`：失败 Bearer 鉴权阈值/窗口，默认 60 秒内 5 次。
- `AUTH_BACKOFF_BASE_SECONDS` / `AUTH_BACKOFF_MAX_SECONDS`：失败来源指数退避，默认 2/60 秒。
- `AUTH_SOURCE_CAPACITY` / `AUTH_SOURCE_TTL_SECONDS`：未认证来源表硬容量和 TTL，默认 4096/3600 秒。达到容量后固定空间 LRU 淘汰；IPv6 按 `/64` 聚合。
- `MAX_CONCURRENT_PROVIDER_CALLS`：所有本机 worker 合计的 provider 在途上限，默认 2。
- `DAILY_PROVIDER_CALL_LIMIT`：每个代理 token 的 provider 尝试上限，默认 200，UTC 00:00 切换日期。
- `PROVIDER_DEADLINE_SECONDS`：provider 总 deadline，默认 20 秒，配置上限 22 秒，明确短于当前 App 的 35 秒读取超时。
- `PROVIDER_RESPONSE_MAX_BYTES`：解压后的流式响应硬上限，默认且最大 1 MiB；声明和实际字节都检查。
- `PROVIDER_LEASE_SECONDS`：异常进程遗留并发槽的过期时间，默认 45 秒，必须至少比 provider deadline 长 5 秒。
- `IDEMPOTENCY_LEASE_SECONDS`：运行中幂等记录租约，默认 45 秒；过期后可由同 key/payload 接管。
- `IDEMPOTENCY_RETENTION_SECONDS` / `IDEMPOTENCY_CAPACITY`：终态完整响应保留期与总记录硬容量，默认 7 天/10000 条；保留期允许 `0`（关闭终态留存），最大 30 天。数据库不含 JPEG 原图，但这些响应仍属于个人饮食数据，状态卷应静态加密。
- `ALLOW_INSECURE_PROVIDER_HTTP`：仅本机开发可用；即使开启也只允许 `localhost`、`127.0.0.1`、`::1`。

## 本地运行

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install --require-hashes -r requirements.lock
$env:PROXY_ACCESS_TOKEN = '<代理令牌>'
$env:VISION_API_BASE_URL = 'https://<兼容服务 Base URL>'
$env:VISION_API_KEY = '<模型 API Key>'
$env:VISION_MODEL = '<视觉模型名>'
$env:STATE_DB_PATH = "$PWD\data\meal-analyzer-state.db"
.\.venv\Scripts\python.exe -m uvicorn meal_analyzer_server:app --host 127.0.0.1 --port 8080 --no-proxy-headers
```

`requirements.txt` 是人工维护的直接依赖，安装和镜像构建使用含 SHA-256 的 `requirements.lock`。升级依赖后重新生成并复核：

```powershell
uv pip compile requirements.txt --universal --python-version 3.12 --generate-hashes --no-strip-extras --output-file requirements.lock
python -m pip install --dry-run --ignore-installed --require-hashes -r requirements.lock
```

## Docker Compose

先在仓库外或被忽略的受限目录分别创建两个单行 secret 文件；不要把它们提交到 Git。把路径写进从 `.env.example` 复制的 `.env`，再启动：

```powershell
Copy-Item .env.example .env
New-Item -ItemType Directory -Force secrets
python -c "import secrets; print(secrets.token_urlsafe(32))" | Set-Content -NoNewline secrets\proxy_access_token.txt
# 将 provider key 写入 secrets\vision_api_key.txt，并收紧两个文件 ACL。
docker compose -f compose.example.yaml config
docker compose -f compose.example.yaml up --build -d
docker compose -f compose.example.yaml ps
```

Compose 将 secret 只读挂载到 `/run/secrets`，把 SQLite 放到本地持久卷，并设置只读根文件系统、非特权 UID、capabilities 全删除、`no-new-privileges`、512 MiB 内存、1 CPU、128 PID 以及 `10m × 3` 日志轮转。8080 只发布到宿主机 loopback，正式手机必须通过受信任证书的 HTTPS 反向代理访问。边缘代理必须覆盖外部自带的 `X-Forwarded-For`，并把实际直接代理对端填入 `FORWARDED_ALLOW_IPS`。

## 健康探针

- `GET /livez`：只证明进程/事件循环仍可响应，返回 `200 {"status":"alive"}`。
- `GET /readyz`：重新校验全部必需配置、deadline/租约关系、secret 文件和 SQLite 可写性；不可处理分析请求时返回 503。

Docker HEALTHCHECK 使用 `/readyz`，不会再把缺模型、缺 provider key 或状态卷不可写的实例标为就绪。

## 请求协议与幂等语义

每次用户发起一次照片分析时生成一个稳定 UUID；因超时、断线或进程重建而重试时必须复用同一个 key 和相同 JSON payload：

```http
POST /analyze-meal
Authorization: Bearer <PROXY_ACCESS_TOKEN>
X-Idempotency-Key: 018f2f34-20df-7c11-a8d2-0242ac120002
Content-Type: application/json
```

```json
{
  "imageBase64": "<JPEG base64>",
  "locale": "zh-CN",
  "requestedFields": "food_candidates,portion_range,preparation,risk_flags",
  "additionalPrompt": "如果照片里是我已称量的同一碗完整熟米饭，其重量为200g；不满足条件时不要套用。"
}
```

`additionalPrompt` 可省略，默认空字符串。只接受字符串（不接受 `null`、数字、布尔值、数组或对象），最多 2000 个 UTF-16 代码单元，与 Android 的字符长度限制一致；允许换行、回车和制表符，拒绝其他 C0、DEL/C1 控制字符及孤立代理码点。验证失败返回 422，不调用上游，也不把原文回显到错误中。

补充资料在模型请求中作为独立 `user` 文本块，以 JSON 的 `additionalContext` 字段引用；不会进入系统指令。系统规则要求只将其作为低优先级、未经核实的食物场景资料，不允许覆盖输出协议、证据等级、风险或来源约束。称重参照必须对应画面食物、熟/生状态和完整份量；200g 熟饭只是条件式例子，并非默认份量。其他菜不能用与米饭的二维面积比直接换算克重，还需保留透视、堆积高度、密度、遮挡和用油的不确定性。用户资料不另存原文到协调库，但其信息可能出现在模型响应中，并随响应按上述策略留存。

服务端按 `token 指纹 + key` 存储规范化 payload 哈希、运行租约和终态 HTTP 响应：

- 相同 key + 相同 payload 的终态直接复用，并返回 `X-Idempotent-Replay: true`，不会再占用日额度或调用 provider。
- 相同 key + 不同 payload 返回 409，防止把一个操作 ID 混用于另一张图。
- 摘要覆盖整个请求 JSON，自动包含 `additionalPrompt`；修改提示也属于不同 payload。省略该字段与显式传空字符串的摘要不同，超时重试必须保持原始字段和值不变。主动修改提示重新分析时应使用新的操作 key，可能再次计费。
- 同 key 正在另一 worker 执行时返回 503 和基于剩余租约的 `Retry-After`，客户端保留同一 key 稍后重试。
- 成功、502 结构/响应错误和意外 500 等不可重试结果会作为终态缓存。明确可重试的 provider 429/503/504 则持久化为 deferred retry lease：`Retry-After` 期间同 key 被合并为在途，期限后同 token/key/payload 才能原子接管并重新执行。
- deferred 接管继续使用同一个派生 provider `Idempotency-Key`，因此等待期不会被快速重试绕过；每次实际尝试仍保守计入本地 UTC 日额度。
- provider 请求同时携带由 token/key 派生的 `Idempotency-Key`。如果进程在 provider 已受理但本地保存响应前崩溃，租约过期后会恢复执行；只有 provider 也支持幂等时才能覆盖这个极窄的跨系统 exactly-once 缺口。不能声称在任意第三方 provider 上绝对只计费一次。

成功响应仍是经过范围校验的候选草稿，例如：

```json
{
  "items": [{
    "name": "米饭（熟）",
    "grams": 180,
    "gramsMin": 140,
    "gramsMax": 230,
    "per100g": {"kcal": 116, "carbsG": 25.9, "proteinG": 2.6, "fatG": 0.3},
    "sourceName": "模型常见值估算",
    "evidenceTier": "C",
    "riskFlags": ["UNKNOWN_OIL"],
    "alternatives": ["糙米饭"]
  }],
  "evidenceTier": "C",
  "evidenceReason": "单图份量和烹调油仍需核对",
  "providerLabel": "AI 估算 · model-name · 单图需确认"
}
```

App 必须继续让用户核对食材、克重、烹调油和酱汁；代理响应不能直接计入正式饮食账本。

## 错误语义

| HTTP | 含义 |
| --- | --- |
| `400` | 请求头、Base64、JPEG 或字段无效，包括缺少/非法幂等 key |
| `401` | Bearer 令牌缺失或错误 |
| `409` | 幂等 key 已被不同 payload 使用 |
| `413` | JSON 请求体或图片过大 |
| `422` | 请求字段类型或约束不符，包括非法/超长 `additionalPrompt`；不调用上游 |
| `429` | 持久请求速率或 UTC 日额度已用尽；含 `Retry-After` |
| `502` | provider 错误、响应超过 1 MiB，或营养结构校验失败 |
| `503` | 配置/协调状态/上游连接暂不可用、同 key 在途或并发槽已满；可重试项含 `Retry-After` |
| `504` | 约 20 秒 provider 总 deadline 超时；该 key 进入持久 deferred lease 并含 `Retry-After` |

provider 尝试即使失败也计入日额度，因为第三方可能已收取费用。容量/额度拒绝发生在 provider 调用前，不会扣减付费尝试；对应幂等 key 会释放以便按 `Retry-After` 安全重试。

## 测试

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
```

测试覆盖跨实例原子并发/限流、重启后的限流/UTC 日额度/幂等结果、payload 冲突、在途/延期/过期租约恢复、可重试失败等待后使用同一 provider key 再执行、不可重试失败终态复用、auth TTL/LRU 硬容量、IPv6 `/64`、readiness、Docker secret 文件、Retry-After 和无 Content-Length 的超大流式响应。成功路径全部使用假的 provider，不会触发付费请求。真实 provider、账单侧幂等和真机/真实照片准确度仍需单独验收。

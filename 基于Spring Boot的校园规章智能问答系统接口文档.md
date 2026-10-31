# 基于 Spring Boot 的校园规章智能问答系统接口文档

版本：V1.2（Spring Boot 3 / Vue 3 技术版本整理稿）  
修订日期：2026-10-15  
依据：《基于 Spring Boot 的校园规章智能问答系统需求分析》V1.2。

技术版本：后端 Spring Boot 3.x + JDK 17 + Spring Security 6.x，前端 Vue 3.x；详细依赖版本基线见需求文档第 2.3 节。具体小版本在工程初始化时锁定；本次修订不改变 V1.1 已定义的接口路径、字段与业务行为。

> 本文为待实现接口契约，不代表接口已经上线。示例文档、条款、账号及统计数据仅演示结构，不代表真实校园规章。JSON 示例中的 data 如未特别说明，均需包在统一响应结构内。

## 1. 通用约定

### 1.1 协议与字段

| 项目 | 约定 |
| --- | --- |
| Base URL | /api |
| 格式 | JSON、UTF-8；上传使用 multipart/form-data |
| 字段 | camelCase；数据库列名转换为响应 DTO，不直接暴露实体 |
| 主键 | BIGINT 主键一律使用 JSON 字符串；UUID、generation 也是字符串；计数、分数、页码使用数字 |
| 时间 | ISO 8601 且包含时区偏移，如 2026-10-15T16:20:00+08:00 |
| 空值 | 对象不存在用 null，数组无元素用 []；字段按契约保留，不混用空字符串 |
| 长度 | 按 Unicode 码点计数；问题、标题、备注去首尾空白；密码不裁剪 |
| 分页 | page ≥ 1；size 默认 10，范围 1～100；非法分页参数返回 400，不静默截断 |
| 排序 | 列表按规定时间排序，并以对应 ID 同向排序打破并列 |
| 日期筛选 | from 包含、to 不包含；均为带偏移时间，from < to；统计最大时间范围 90 天，未传时默认最近 30 天 |
| 未定义字段 | 不允许客户端提交 userId、roleType 等服务端控制字段，出现越权控制字段返回 400 |
| 时限 | 提问同步处理，服务端总时限 60 秒；前端等待建议 65 秒；上传/重建异步，3～5 秒轮询详情 |

分类代码固定为：STUDENT_STATUS（学籍管理）、DISCIPLINE（奖惩办法）、ACADEMIC_AFFAIRS（教务流程）、DORMITORY（宿舍管理）。请求与响应均用代码，中文名称由前端映射。

### 1.2 认证与授权

- 仅 POST /api/auth/login 公开。其他业务接口须携带 Authorization: Bearer <token>。
- JWT 包含 sub（userId）、roleType、iat、exp；默认有效期 7200 秒。服务端校验签名、有效期及账号 enabled 状态，角色以服务端可信信息为准。
- /api/qa/** 仅 STUDENT；/api/admin/** 仅 ADMIN。权限由 Spring Security 6.x 和业务服务执行，采用 SecurityFilterChain 配置方式，首期不设置独立网关。
- 学生接口必须校验会话、消息和来源归属；不存在和属于他人的资源统一返回 404，避免泄露资源存在性。
- 管理员只通过指定反馈接口查看关联的问题/回答/来源，不默认开放全部学生会话。
- 首期不提供刷新令牌、注册、找回密码接口。退出由前端清除令牌；该动作不撤销已签发 JWT，令牌在过期前仍可能有效。

### 1.3 统一响应与 HTTP 状态

成功响应示例：

~~~json
{
  "code": "0",
  "message": "success",
  "data": {},
  "requestId": "req-20261015-0001"
}
~~~

错误响应示例：

~~~json
{
  "code": "Q-0004",
  "message": "检索服务暂不可用，请稍后重试",
  "data": {
    "clientRequestId": "f5d5d601-6ac7-4d13-8e21-9b8011a3f341",
    "userMessageId": "194823810493282561",
    "retryable": true
  },
  "requestId": "req-20261015-0002"
}
~~~

code 始终为字符串，成功为 "0"；requestId 为本次 HTTP 请求的服务端追踪标识，与提问幂等标识 clientRequestId 不同。错误 data 无附加信息时为 null；不返回堆栈、私有路径或密钥。

| HTTP | 含义 |
| --- | --- |
| 200 | 查询、修改、登录、提问处理或幂等重放成功 |
| 201 | 新会话创建成功 |
| 202 | 文档上传、重建或删除清理已受理，不代表已完成 |
| 400 | 参数缺失、格式/枚举/长度非法 |
| 401 / 403 | 未认证或凭证错误 / 已认证但角色无权访问或账号被禁用 |
| 404 | 资源不存在，或学生请求的资源不属于本人 |
| 409 | 状态冲突、同会话正在处理、幂等标识冲突 |
| 413 / 415 | 上传超过大小限制 / 不支持的文件格式 |
| 429 | 请求频率受限，响应携带 Retry-After 秒数 |
| 500 / 502 / 503 / 504 | 内部错误 / 外部模型失败 / 检索服务不可用 / 处理超时 |

业务拒答 NO_MATCH、INSUFFICIENT_EVIDENCE 属于成功处理，HTTP 200、code="0"，通过 answerStatus 区分，不使用错误码模拟未命中。

分页 data 固定结构：

~~~json
{
  "total": 23,
  "page": 1,
  "size": 10,
  "records": []
}
~~~

## 2. 接口清单

| 编号 | 方法 | 路径 | 说明 | 角色 |
| --- | --- | --- | --- | --- |
| API-01 | POST | /api/auth/login | 登录 | 公开 |
| API-02 | POST | /api/qa/sessions | 创建会话 | 学生 |
| API-03 | GET | /api/qa/sessions | 本人会话列表 | 学生 |
| API-04 | GET | /api/qa/sessions/{sessionId} | 会话详情与分页消息 | 学生 |
| API-05 | POST | /api/qa/sessions/{sessionId}/questions | 同步提问 | 学生 |
| API-06 | PUT | /api/qa/messages/{messageId}/feedback | 创建或覆盖本人评价 | 学生 |
| API-07 | PUT | /api/qa/sessions/{sessionId}/close | 幂等关闭会话 | 学生 |
| API-08 | POST | /api/admin/documents | 上传并异步处理 | 管理员 |
| API-09 | GET | /api/admin/documents | 文档分页列表 | 管理员 |
| API-10 | PATCH | /api/admin/documents/{docId} | 部分修改元数据 | 管理员 |
| API-11 | POST | /api/admin/documents/{docId}/revectorize | 全量重建索引 | 管理员 |
| API-12 | DELETE | /api/admin/documents/{docId} | 停用并异步清理文档 | 管理员 |
| API-13 | GET | /api/admin/monitor/summary | 知识库状态汇总 | 管理员 |
| API-14 | GET | /api/admin/documents/{docId} | 文档与任务详情 | 管理员 |
| API-15 | GET | /api/qa/messages/{messageId}/sources/{sourceId} | 本人回答的引用原文 | 学生 |
| API-16 | GET | /api/admin/monitor/qa | 问答质量统计 | 管理员 |
| API-17 | GET | /api/admin/feedback | 反馈分页查询 | 管理员 |

相对原稿，API-06 改为 PUT，API-10 改为 PATCH；API-04/10/12 的资源 ID 路径已补齐。尚未实现阶段直接采用本契约，无须兼容旧路径。

## 3. 认证接口

### API-01 登录

POST /api/auth/login

请求：

~~~json
{
  "username": "stu2023001",
  "password": "example-password"
}
~~~

username 为 1～64 字符；password 必填、最大 72 个 UTF-8 字节，匹配 BCrypt 输入约束；响应不得回显密码。账号初始化采用相同约束。

HTTP 200：

~~~json
{
  "code": "0",
  "message": "success",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "tokenType": "Bearer",
    "expiresIn": 7200,
    "user": {
      "userId": "194823710493282304",
      "username": "stu2023001",
      "realName": "张三",
      "roleType": "STUDENT",
      "studentNo": "2023001"
    }
  },
  "requestId": "req-20261015-0001"
}
~~~

用户名或密码错误统一 A-0001（401），不区分原因；密码正确但账号禁用返回 A-0004（403）。登录频率超过配置返回 C-0002（429）。

## 4. 学生会话与问答

### API-02 创建会话

POST /api/qa/sessions；请求体省略或为 {}，不接受指定归属用户。

HTTP 201 的 data：

~~~json
{
  "sessionId": "194823810493282560",
  "title": "新会话",
  "status": "ACTIVE",
  "createTime": "2026-10-15T16:20:00+08:00",
  "lastActiveTime": "2026-10-15T16:20:00+08:00"
}
~~~

首次接收有效问题后，标题取原问题前 30 个字符；不额外调用模型生成标题。

### API-03 本人会话列表

GET /api/qa/sessions?page=1&size=10&status=ACTIVE

status 可选 ACTIVE/CLOSED，不传则返回两种状态。按 lastActiveTime、sessionId 降序。records 为 SessionItem，结构同 API-02；total 仅计算当前学生符合条件的会话。

### API-04 会话详情与分页消息

GET /api/qa/sessions/{sessionId}?page=1&size=20

按 createTime、messageId 升序分页；page/size 针对消息，非会话。首次默认返回最早一页；前端可根据 total 翻到末页。USER 与 ASSISTANT 可能跨页，通过 replyToMessageId 关联。

data：

~~~json
{
  "session": {
    "sessionId": "194823810493282560",
    "title": "考试作弊处理有什么程序要求？",
    "status": "ACTIVE",
    "createTime": "2026-10-15T16:20:00+08:00",
    "lastActiveTime": "2026-10-15T16:21:00+08:00"
  },
  "messages": {
    "total": 2,
    "page": 1,
    "size": 20,
    "records": []
  }
}
~~~

records 使用第 6 节 MessageItem：USER 包含处理状态、改写问题和错误码；ASSISTANT 包含 answerStatus、sources 和本人 feedback。历史恢复不得只返回回答文本、丢失来源与评价。

### API-05 同步提问

POST /api/qa/sessions/{sessionId}/questions

请求：

~~~json
{
  "clientRequestId": "f5d5d601-6ac7-4d13-8e21-9b8011a3f341",
  "content": "考试作弊处理有什么程序要求？"
}
~~~

clientRequestId 为必填 UUID，由客户端为每个新问题生成；网络重试复用原值。content 去首尾空白后为 1～1000 字符；客户端不能传阈值、模型提示词或指定检索他人资源。

处理顺序：认证与归属 → 参数及幂等检查 → 会话状态与并发检查 → 保存 USER → 按需改写 → 嵌入与检索 → 过滤有效代次与阈值 → 生成/拒答及引用校验 → 复核文档可用性 → 事务保存回答与来源。首轮跳过改写，rewrittenQuery 返回去首尾空白后的原问题。

HTTP 200（下例条款仅为结构示例）：

~~~json
{
  "code": "0",
  "message": "success",
  "data": {
    "sessionId": "194823810493282560",
    "clientRequestId": "f5d5d601-6ac7-4d13-8e21-9b8011a3f341",
    "rewrittenQuery": "考试作弊处理有什么程序要求？",
    "matched": true,
    "answerStatus": "ANSWERED",
    "userMessage": {
      "messageId": "194823810493282561",
      "role": "USER",
      "content": "考试作弊处理有什么程序要求？",
      "processingStatus": "SUCCEEDED",
      "createTime": "2026-10-15T16:20:01+08:00"
    },
    "assistantMessage": {
      "messageId": "194823810493282562",
      "role": "ASSISTANT",
      "replyToMessageId": "194823810493282561",
      "content": "示例条款要求，考试作弊行为的认定和处分依该办法规定的程序处理。[1]",
      "createTime": "2026-10-15T16:20:05+08:00"
    },
    "sources": [
      {
        "sourceId": "194823810493282563",
        "rankNo": 1,
        "chunkId": "194823810493282540",
        "docId": "194823810493282520",
        "generation": "d7253508-ef92-4581-8f4d-906418853980",
        "docTitle": "学生违纪处理办法（结构示例）",
        "category": "DISCIPLINE",
        "sectionLabel": "第十二条",
        "pageStart": 3,
        "pageEnd": 3,
        "similarityScore": 0.8923,
        "snippet": "第十二条 考试作弊行为的认定和处分，依本办法规定程序处理。",
        "sourceStatus": "ACTIVE"
      }
    ]
  },
  "requestId": "req-20261015-0003"
}
~~~

结果约束：

| answerStatus | matched | assistantMessage | sources |
| --- | --- | --- | --- |
| ANSWERED | true | 必须存在，正文用 [1]、[2] 对应来源编号 | 至少 1 项，只包含正文实际引用的分块 |
| NO_MATCH | false | 必须存在，content 固定为“未找到相关规章条款” | []；不调用回答生成模型 |
| INSUFFICIENT_EVIDENCE | true | 必须存在，content 固定为“现有规章条款不足以回答该问题” | []；召回但未支持结论的分块不冒充引用 |

matched 只表示有分块通过有效性与相似度过滤，不表示答案一定可靠。三种结果都保存完整的一对消息；message 统一为 success，前端依据 answerStatus 展示。技术异常返回非成功 HTTP，不返回 matched=false 伪装拒答。

幂等与并发：

- 同一会话、相同 clientRequestId、相同规范化 content，若已成功则返回原消息与来源，不重复触发模型调用；会话已关闭也可读取该已完成结果。消息与引用快照不变，sourceStatus 按查询时状态重算。
- 同一标识但内容不同返回 Q-0008（409）；该请求仍在处理中返回 Q-0007（409）。
- 已失败的请求仅在仍是会话最新问题、会话仍 ACTIVE 时可复用标识重试；复用 USER 消息并增加 attemptCount；成功后只生成一条对应 ASSISTANT。
- 会话存在其他处理中问题，或处理中时请求关闭会话，返回 Q-0007（409）。
- 新问题若未通过接收前校验，不写消息；接收后技术失败，USER 标记 FAILED，并在错误 data 中返回 userMessageId/clientRequestId/retryable。数据库本身不可用时错误记录可暂时失败，由超时/启动恢复释放遗留处理状态，日志保留 requestId。

常见错误：Q-0001～Q-0012，详见第 7 节。

### API-06 创建或覆盖评价

PUT /api/qa/messages/{messageId}/feedback

~~~json
{
  "rating": "LIKE",
  "remark": "条款引用准确"
}
~~~

rating 必填 LIKE/DISLIKE；remark 可省略或为 null，最多 500 字符。只允许评价本人会话中的 ASSISTANT，包括拒答提示。评价 USER 消息返回 F-0002（400）；目标不存在或不属于本人返回 F-0001（404）。

同 messageId + 当前用户唯一，重复请求覆盖当前评价，保留 createTime、更新 updateTime。HTTP 200，data 为 FeedbackItem（第 6 节）；无撤销评价接口。备注未传或 null 表示清空备注。

### API-07 关闭会话

PUT /api/qa/sessions/{sessionId}/close；无请求体。

ACTIVE → CLOSED；CLOSED 再次关闭 HTTP 200；有问题处理中返回 Q-0007。不存在/他人会话返回 Q-0002。data：

~~~json
{
  "sessionId": "194823810493282560",
  "status": "CLOSED"
}
~~~

仅定义 ACTIVE/CLOSED，不使用未定义的“其他终态”。关闭操作及成功接收问题更新 lastActiveTime；分页查询与幂等结果重放不更新活跃时间。

### API-15 查看引用原文

GET /api/qa/messages/{messageId}/sources/{sourceId}

校验消息属于当前学生，且 sourceId 确实关联该 ASSISTANT 消息。data 为 SourceItem 加 fullText（引用时保存的完整分块文本）。不直接按 chunkId 开放任意知识库片段。

sourceStatus：ACTIVE=文档仍有效且代次相同；SUPERSEDED=已切换新代次；DELETED=文档 DELETING/DELETED；UNAVAILABLE=当前对应文档或有效代次不可用。后三者仍返回历史快照，并提示该证据不代表当前有效规章。Redis 暂时故障不影响历史快照查询。不存在/不匹配/他人来源统一 Q-0011（404）。

## 5. 管理员接口

### API-08 上传并异步处理

POST /api/admin/documents，multipart/form-data。

| 字段 | 类型 | 必填 | 约束 |
| --- | --- | --- | --- |
| file | file | 是 | 单文件 PDF/DOCX/UTF-8 TXT；最大 20 MiB；扩展名与内容格式须一致 |
| title | string | 是 | 去首尾空白后 1～100 字符 |
| category | string | 是 | 第 1 节定义的分类代码 |

接收阶段超限 413、格式不支持 415、参数错误 400。解析阶段才发现的加密、损坏、无文本、页数/字符/分块超限，不回改上传响应，记录任务失败原因。

原文件保存及数据库登记成功后，HTTP 202，data：

~~~json
{
  "docId": "194823810493282520",
  "title": "学生违纪处理办法（结构示例）",
  "category": "DISCIPLINE",
  "fileType": "PDF",
  "lifecycleStatus": "ACTIVE",
  "vectorStatus": "PENDING",
  "searchable": false,
  "chunkCount": 0,
  "taskId": "09301439-996e-4d47-873e-cf5cbca52ae8",
  "uploadTime": "2026-10-15T16:20:00+08:00"
}
~~~

轮询 API-14 获取状态；后端实际执行时状态可能已经推进。请求没有收到响应时，前端先刷新列表确认，不自动反复上传。首期不做内容去重，fileHash 用于追踪，不保证相同文件只入库一次。

### API-09 文档分页列表

GET /api/admin/documents?page=1&size=10&vectorStatus=FAILED&category=DISCIPLINE&keyword=违纪&lifecycleStatus=ACTIVE

支持 vectorStatus、category、lifecycleStatus、keyword 筛选；keyword 最多 100 字符，按标题包含匹配；枚举非法返回 400。lifecycleStatus 不传时返回 ACTIVE 和 DELETING，显式 DELETED 可查看已删除记录。按 uploadTime、docId 降序。

data 为分页结构，records 为 DocumentItem（第 6 节），至少包含 lastError 和 cleanupError，避免后台只看到 FAILED 却无法定位。

### API-14 文档与任务详情

GET /api/admin/documents/{docId}

data 为 DocumentItem 加 originalFilename、fileSize、activeGeneration、currentTask；不返回本地 filePath。已逻辑删除但保留的记录仍可由管理员查询。

~~~json
{
  "docId": "194823810493282520",
  "title": "学生违纪处理办法（结构示例）",
  "category": "DISCIPLINE",
  "fileType": "PDF",
  "lifecycleStatus": "ACTIVE",
  "vectorStatus": "FAILED",
  "searchable": true,
  "chunkCount": 42,
  "uploaderId": "194823710493282305",
  "uploadTime": "2026-10-15T16:20:00+08:00",
  "updateTime": "2026-10-15T16:25:00+08:00",
  "lastError": {
    "code": "T-0004",
    "stage": "EMBEDDING",
    "message": "嵌入模型调用失败"
  },
  "cleanupError": null,
  "originalFilename": "示例办法.pdf",
  "fileSize": 102400,
  "activeGeneration": "d7253508-ef92-4581-8f4d-906418853980",
  "currentTask": {
    "taskId": "09301439-996e-4d47-873e-cf5cbca52ae8",
    "status": "FAILED",
    "stage": "EMBEDDING",
    "startedTime": "2026-10-15T16:24:00+08:00",
    "finishedTime": "2026-10-15T16:25:00+08:00"
  }
}
~~~

例中最近重建失败，但旧代次仍可检索，所以 searchable=true。stage 为 QUEUED/PARSING/CHUNKING/EMBEDDING/INDEXING/FINALIZING/DONE；status 对应最近任务四态。PENDING 的 startedTime/finishedTime 为 null；PROCESSING 的 finishedTime 为 null。详情仅保存最近处理任务，不承诺完整任务历史或精确百分比。

### API-10 修改元数据

PATCH /api/admin/documents/{docId}

~~~json
{
  "title": "学生违纪处理办法（展示标题）",
  "category": "DISCIPLINE"
}
~~~

至少传一个字段；每个已传字段均须通过长度/枚举校验，不接受 null 或空字符串。仅 ACTIVE 文档可修改，其他生命周期返回 D-0006（409）。

HTTP 200，data 为更新后的 DocumentItem。仅修改标题/分类，不替换原文、不重建嵌入；查询展示从 MySQL 获取最新元数据。已有 answer_source 的标题/分类快照不变。不能用标题中的“2026 修订”宣称文件正文已经更新。

### API-11 全量重建索引

POST /api/admin/documents/{docId}/revectorize；无请求体。

- 仅 lifecycleStatus=ACTIVE 且 vectorStatus 为 FAILED/COMPLETED 时受理，包括已完成但分块异常或需要恢复丢失向量的文档。
- PENDING/PROCESSING 返回 D-0005（409）；DELETING/DELETED 返回 D-0006（409）。
- 原子登记新 taskId 和目标 generation，置 PENDING 后由后台执行；成功激活新代次，失败保留原可用代次。
- 受理后旧 task 错误清空，但原分块数和 activeGeneration 保持，直至新代次成功切换。旧代次清理异常记录 cleanupError，不将已成功激活的新代次回滚为失败。

HTTP 202，data：

~~~json
{
  "docId": "194823810493282520",
  "taskId": "09301439-996e-4d47-873e-cf5cbca52ae8",
  "vectorStatus": "PENDING",
  "searchable": true
}
~~~

并发或任务未结束期间重复提交只受理一次，其余返回 409；首期不承诺任务完成后再次 POST 的幂等重放。前端等待任务结束后方可再次主动发起。

### API-12 停用并清理文档

DELETE /api/admin/documents/{docId}；无请求体。

立即将 ACTIVE 改为 DELETING，新的检索与回答证据复核必须排除此文档；异步清理各代次向量、私有原文件并逻辑删除。任务进行中删除也允许，但旧处理任务不得再发布结果，清理任务须涵盖其晚到写入或在其结束后再次核对。

HTTP 202 的 data：

~~~json
{
  "docId": "194823810493282520",
  "lifecycleStatus": "DELETING"
}
~~~

清理状态通过 API-14 查询。DELETING 时重复 DELETE：已有清理在执行则返回当前受理状态，前次清理失败则重试；已 DELETED 时 HTTP 200，data 为 {"docId":"194823810493282520","lifecycleStatus":"DELETED"}。从未存在的 ID 返回 D-0004（404）。

清理失败仍保持 DELETING，并在 cleanupError 返回 {code,stage,message}；不能返回 deleted=true 声称已完成。历史分块、消息和来源快照保留，不级联删除。前端提供删除确认弹窗，弹窗不替代后端权限控制。

### API-13 知识库状态汇总

GET /api/admin/monitor/summary

~~~json
{
  "totalDocuments": 18,
  "totalChunks": 763,
  "completedCount": 16,
  "completionRate": 0.8889,
  "searchableDocumentCount": 17,
  "deletingCount": 1,
  "pendingDeletionCount": 1,
  "statusDistribution": {
    "PENDING": 0,
    "PROCESSING": 1,
    "COMPLETED": 16,
    "FAILED": 1
  }
}
~~~

口径：

- totalDocuments 和四态分布仅统计 lifecycleStatus=ACTIVE，四类数量之和等于 totalDocuments。
- completedCount 为最近任务 COMPLETED 数；completionRate=completedCount/totalDocuments，分母为 0 时为 0。
- searchableDocumentCount 统计 ACTIVE 且 activeGeneration 非空的文档；totalChunks 统计其当前有效代次分块，不累计历史/临时代次。
- deletingCount 单列 DELETING 文档；DELETED 不参与上述统计。重建失败但保有旧代次可检索，故完成数可能小于可检索数。
- pendingDeletionCount 统计 DELETING 且未记录清理失败的文档，供前端判断是否继续刷新。清理失败时仍计入 deletingCount，但不计入 pendingDeletionCount；手动重试删除时清除上次清理错误并重新计入，成功删除后不再计入。

### API-16 问答质量统计

GET /api/admin/monitor/qa?from=2026-10-01T00:00:00%2B08:00&to=2026-10-16T00:00:00%2B08:00

查询参数中的正号须 URL 编码为 %2B。data：

~~~json
{
  "totalQuestions": 100,
  "succeededCount": 90,
  "failedCount": 7,
  "processingCount": 3,
  "answeredCount": 70,
  "noMatchCount": 15,
  "insufficientEvidenceCount": 5,
  "retrievalHitCount": 75,
  "retrievalHitRate": 0.8333,
  "answerRate": 0.7778,
  "failureRate": 0.0722,
  "topSources": [
    {
      "docId": "194823810493282520",
      "docTitle": "学生违纪处理办法（结构示例）",
      "citationCount": 26
    }
  ]
}
~~~

以 USER 消息 createTime 落入 [from,to) 为范围，每个逻辑问题计一次，按当前最新尝试状态统计；重试会改变当前状态，成功幂等重放不增加问题数。

totalQuestions=succeededCount+failedCount+processingCount；succeededCount=answeredCount+noMatchCount+insufficientEvidenceCount。retrievalHitCount=answeredCount+insufficientEvidenceCount，retrievalHitRate=retrievalHitCount/succeededCount，answerRate=answeredCount/succeededCount，failureRate=failedCount/(succeededCount+failedCount)；分母为 0 时为 0，比例四舍五入保留 4 位。

topSources 为该范围内成功答案的实际来源记录按 docId 聚合，取引用记录数前 10，按 citationCount、docId 降序；同一答案引用同文档不同分块计多条。docTitle 取该文档在范围内最近一次引用的标题快照，历史文档删除后仍可统计。此接口不等价于回答正确率评测。

### API-17 反馈分页查询（中优先级）

GET /api/admin/feedback?page=1&size=10&rating=DISLIKE&from=2026-10-01T00:00:00%2B08:00&to=2026-10-16T00:00:00%2B08:00

rating 可选 LIKE/DISLIKE；时间筛选以反馈 updateTime 为准。按 updateTime、feedbackId 降序。data 为分页结构，records：

~~~json
[
  {
    "feedbackId": "194823810493282570",
    "messageId": "194823810493282562",
    "rating": "DISLIKE",
    "remark": "希望补充具体办理部门",
    "createTime": "2026-10-15T16:22:00+08:00",
    "updateTime": "2026-10-15T16:22:00+08:00",
    "question": "考试作弊处理有什么程序要求？",
    "answer": "示例条款要求，考试作弊行为的认定和处分依该办法规定的程序处理。[1]",
    "answerStatus": "ANSWERED",
    "sources": [
      {
        "sourceId": "194823810493282563",
        "rankNo": 1,
        "chunkId": "194823810493282540",
        "docId": "194823810493282520",
        "generation": "d7253508-ef92-4581-8f4d-906418853980",
        "docTitle": "学生违纪处理办法（结构示例）",
        "category": "DISCIPLINE",
        "sectionLabel": "第十二条",
        "pageStart": 3,
        "pageEnd": 3,
        "similarityScore": 0.8923,
        "snippet": "第十二条 考试作弊行为的认定和处分，依本办法规定程序处理。",
        "sourceStatus": "ACTIVE",
        "fullText": "第十二条 考试作弊行为的认定和处分，依本办法规定程序处理。"
      }
    ]
  }
]
~~~

sources 返回对应 SourceItem 数组，每项额外包含 fullText（引用时的完整分块快照），供管理员评估引用；不返回其他会话消息、学生身份字段、私有文件路径。

## 6. 关键对象

### MessageItem（用于历史查询）

共同字段：messageId、sessionId、role、content、createTime。

- USER 补充 clientRequestId、processingStatus(PROCESSING/SUCCEEDED/FAILED)、rewrittenQuery（未完成改写可为 null）、errorCode（非失败为 null）、attemptCount。
- ASSISTANT 补充 replyToMessageId、answerStatus、sources（SourceItem[]）、feedback（FeedbackItem 或 null）。
- ASSISTANT 只保存成功处理的业务结果；技术失败展示 USER 上的失败状态，不造一条带错误堆栈的回答。
- API-05 为避免重复，answerStatus 和 sources 位于 data 顶层，userMessage/assistantMessage 使用精简对象；API-04 将它们放回对应 MessageItem。

### SourceItem

字段：sourceId、rankNo、chunkId、docId、generation、docTitle、category、sectionLabel、pageStart、pageEnd、similarityScore、snippet、sourceStatus。

docTitle/category/sectionLabel/页码/snippet 来自引用时快照；snippet 为快照前 300 字符。PDF 页码为从 1 开始的文件物理页码，不等同于纸面印刷页码；无法确定时为 null，TXT/DOCX 不编造页码。rankNo 从 1 连续递增，与正文引用编号一致；similarityScore 为 [-1,1] 的统一余弦相似度，底层若返回距离须先转换。

### FeedbackItem

feedbackId、messageId、rating、remark（可 null）、createTime、updateTime。响应不允许客户端借 userId 指定他人评价。

### DocumentItem

docId、title、category、fileType(PDF/DOCX/TXT)、lifecycleStatus(ACTIVE/DELETING/DELETED)、vectorStatus(PENDING/PROCESSING/COMPLETED/FAILED)、searchable、chunkCount、uploaderId、uploadTime、updateTime、lastError（{code,stage,message} 或 null）、cleanupError（同结构或 null）。

searchable 是文档是否具有已激活知识的派生状态，不保证 Redis 服务此刻在线。chunkCount 为当前有效代次块数；无有效代次为 0；DELETING/DELETED 可保留最后有效代次计数供审计，但不计入汇总 totalChunks。

## 7. 错误码

### 7.1 HTTP 请求错误

| 错误码 | HTTP | 含义 |
| --- | --- | --- |
| C-0001 | 400 | 通用参数错误、非法枚举/分页/时间/ID |
| C-0002 | 429 | 超过请求频率限制 |
| C-0003 | 500 | 内部存储或未分类错误，不暴露细节 |
| A-0001 | 401 | 用户名或密码错误 |
| A-0002 | 401 | 未携带令牌、令牌无效或过期 |
| A-0003 | 403 | 角色无权访问 |
| A-0004 | 403 | 账号已禁用 |
| Q-0001 | 400 | 问题为空、超长或 clientRequestId 非法 |
| Q-0002 | 404 | 会话不存在或不属于当前用户 |
| Q-0003 | 409 | 会话已关闭，不可接收新问/失败重试 |
| Q-0004 | 503 | Redis 检索服务不可用 |
| Q-0005 | 502 | 问题改写或回答生成模型失败 |
| Q-0006 | 502 | 问题嵌入模型失败 |
| Q-0007 | 409 | 会话存在处理中请求 |
| Q-0008 | 409 | 幂等标识内容冲突或失败问题已有后续问题 |
| Q-0009 | 504 | 问答处理超时 |
| Q-0010 | 502 | 模型输出格式或引用无效，未产生可发布回答 |
| Q-0011 | 404 | 来源不存在、不属于该消息或无权查看 |
| Q-0012 | 409 | 回答生成期间所用文档/代次变化，请重试 |
| F-0001 | 404 | 被评价消息不存在或不属于本人 |
| F-0002 | 400 | 目标非 ASSISTANT、评价枚举或备注非法 |
| D-0001 | 415 | 文件格式不支持或内容与扩展名不符 |
| D-0002 | 413 | 文件超过 20 MiB |
| D-0003 | 400 | 文件缺失或标题/分类非法 |
| D-0004 | 404 | 文档不存在 |
| D-0005 | 409 | 文档任务已排队或处理中 |
| D-0006 | 409 | 文档正在删除或已删除，不能修改/重建 |

处理前错误一般 data=null。处理后错误附提问标识与 retryable；Q-0004/5/6/9/10/12、可恢复 C-0003 可重试，仍须遵守最新问题与会话开启条件。无效引用不能降级成正常回答。

### 7.2 异步任务错误（仅通过状态详情返回）

| 错误码 | 说明 |
| --- | --- |
| T-0001 | 文档加密、损坏或解析失败 |
| T-0002 | 没有可提取文本，可能是扫描件 |
| T-0003 | 解析后页数/文本/分块数量超限 |
| T-0004 | 文档分块嵌入失败 |
| T-0005 | 向量写入/校验失败 |
| T-0006 | 文档自受理起超过 10 分钟（含排队） |
| T-0007 | 服务重启导致任务中断 |
| T-0008 | 文件/数据库保存或清理失败 |
| T-0009 | Redis 向量清理失败 |

任务错误不改变已经返回的 HTTP 202。任务失败更新 vectorStatus=FAILED；删除清理失败保持 lifecycleStatus=DELETING。新代次已激活后的旧向量清理失败只更新 cleanupError，不把成功的处理任务改为 FAILED。

## 8. 实现与联调约束

1. 使用单实例后台执行器与数据库任务字段；受理、执行、失败、重启恢复均须落库。PENDING/PROCESSING 超时或重启后不能永久卡住。
2. MySQL 事务不覆盖 Redis，使用 generation 隔离、激活指针、候选有效性检查与补偿清理。上传、重建、删除并发时均需校验生命周期和 taskId，旧任务不得覆盖新任务。
3. 答案与来源必须同一事务提交；NO_MATCH/INSUFFICIENT_EVIDENCE 同样保存 ASSISTANT。数据库不可用时保留可恢复状态，客户端通过历史与同 clientRequestId 确认结果。
4. 引用按正文实际使用顺序记录，历史查询直接读快照；不能把全部检索候选都标成答案来源，不能仅依赖可删除的 Redis 数据回显。
5. 阈值、TOP_K、模型、分块与上下文预算由服务端配置，采用需求文档第 5 节基线；模型和向量维度必须匹配。参数变更记录到测试报告。
6. 接口级验证至少覆盖：跨学生越权、未命中持久化、来源回显、同会话并发、网络重复请求、重建失败保留旧知识、删除与处理并发、Redis 清理失败、进程重启恢复。
7. Spring Boot 3 后端使用 Jakarta Servlet/Validation 等依赖与 jakarta.* 包名，避免引入仅支持 Spring Boot 2 的集成包。Spring Framework、Spring Security 和默认 Tomcat 的具体版本随 Spring Boot BOM 管理。
8. Vue 3 前端按本文的字符串 ID、字符串 code、ISO 8601 时间及统一响应结构处理数据；前端路由守卫不代替后端授权。Vue Router 若使用则采用 4.x；构建工具和其他依赖遵循需求文档第 2.3 节并提交锁文件。


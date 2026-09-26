# 多个视觉模型服务

更新：2026-09-22；alpha09 / code 26，数据库仍 v11。

## 使用方式

在设置的“整餐 AI 识别”中新增连接，选择 Qwen、DeepSeek、GLM 或自定义预置，填写服务名称和该账号的 API Key。地址与模型 ID 都可修改。同一家也可保存多个不同连接，最多 20 条；不是只能保存三个固定品牌。

保存并启用后，其他连接仍保留。饮食页“整餐拍一次”右侧显示当前服务，点开即可切换；切换本身不分析照片、不改变已有草稿或摄入。服务失效时不会偷偷改用其他供应商，只有用户明确重试才发新请求。删除当前服务后停用上传，不自动启用另一家。保存配置不等于已完成真实联网验证。

## 预置与官方依据

以下是本次核实的默认示例，不保证本人账号已开通，也不是营养准确率排名。

| 服务 | 默认模型 | Base URL | 请求差异 |
| --- | --- | --- | --- |
| Qwen / 阿里云百炼 | `qwen3-vl-plus` | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `enable_thinking:false`，JSON Object |
| DeepSeek | `deepseek-flash` | `https://api.deepseek.com` | `thinking:{type:"disabled"}`，JSON Object |
| GLM / 智谱 | `glm-4.6v` | `https://open.bigmodel.cn/api/paas/v4` | `thinking:{type:"disabled"}`；不发仅文本模型支持的 `response_format` |

GLM可将模型ID改为 `glm-5.3-flash`：alpha09会发送 `reasoning_effort:"low"`，不发送 `thinking` 开关或 `response_format`。升级保留现有模型选择，不自动切换模型或使用其他服务的Key；发送时仅规范官方GLM-5.3-Flash的大小写变体。未知GLM模型不再自动附带关闭思考参数。[本轮修复与验证边界](glm53-flash-compatibility.md)。

三家均使用非流式图片版 Chat Completions，发送一张去 EXIF 的 JPEG；`system` 为内置整餐估算规则，`user` 为照片及 JSON 转义封装的附加说明。Qwen 旧北京域名仍可使用；也可粘贴自己的业务空间 API Host，注意区域与 Key 一致。[Qwen 兼容接口](https://help.aliyun.com/zh/model-studio/qwen-api-via-openai-chat-completions)、[结构化输出](https://help.aliyun.com/zh/model-studio/qwen-structured-output)。

DeepSeek 官方当前明确 `deepseek-flash` 接收 `image_url` Base64 Data URL，不沿用旧的“不支持图片”结论。只支持文本的模型不能靠填写品牌名变成视觉模型。[图像理解](https://api-docs.deepseek.com/zh-cn/guides/vision/)、[首次调用](https://api-docs.deepseek.com/zh-cn/)、[思考控制](https://api-docs.deepseek.com/guides/thinking_mode/)、[JSON 输出](https://api-docs.deepseek.com/guides/json_mode/)。

GLM 默认使用视觉模型 `glm-4.6v`，不是普通文本模型。其接口的强制 JSON 参数仅注明文本模型支持，因此用提示约束加客户端严格解析，不假定视觉 JSON 模式可用。[GLM-4.6V](https://docs.bigmodel.cn/cn/guide/models/vlm/glm-4.6v)、[对话补全接口](https://docs.bigmodel.cn/api-reference/模型-api/对话补全)、[官方 SDK Data URL 示例](https://github.com/MetaGLM/zhipuai-sdk-python-v4/blob/main/README_CN.md)。

## 凭据与迁移

“附加提示词”是所有服务共用的单独业务设置，不包含在模型连接中。切换或删除服务不会清空它；下次照片识别才发送，保存本身不会调用模型。说明见[提示词与已知份量参照](meal-analysis-prompts.md)。

- 旧连接升级迁移为已保存连接，不改旧模型或真实 Key。旧自建代理仍保留其协议，通过高级代理设置编辑。
- 配置集合与各自密钥由 Android Keystore 加密，UI 只获得无密钥元数据。API Key 不进入日志、可恢复表单或备份。
- 留空保留 Key 只限正在编辑的同一条、精确相同地址与协议；改地址必须重新输入并确认上传目的地，不借用其他连接的 Key。
- 保存集合和激活状态同时提交；重复迁移不会重复新增；删除当前项不会自动选择另一家。
- 高级代理中的删除或空地址保存先确认，只删除当前连接并保留其他服务；删除全部服务必须在上方单独确认。
- 加密备份继续排除模型连接、密钥和小米凭据；恢复备份后需重新配置，不从旧槽位复活上传。

## 验证边界

本次使用合成密钥与响应测试保存、切换、迁移、密钥隔离及请求参数，不调用付费 API。真实账号权限、配额、每家接口的端到端表现与营养误差仍需用户真机验证。照片营养仍是可修改估算，必须确认后入账；未改变这一边界。

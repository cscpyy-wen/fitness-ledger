# 小米云端协议共享源码

从已实测通过的独立 `xiaomi-probe` 抽出的协议实现，供探针和主 App 共用，避免各自维护一份加密/域名校验代码。两模块通过 Gradle sourceSets 编译相同源码，不新增网络依赖。

源代码采用 GPL-3.0-or-later；完整许可和来源详见 `../xiaomi-probe/LICENSE`、`../xiaomi-probe/NOTICE`，主 App 也在 assets/licenses 中携带许可与变更说明。2026-09-21 新增的会话恢复和有界 1–56 天查询由主 App 明示授权后使用；独立探针仍仅在内存中保存会话、只读近 7 天。

当前是用户委托的个人本机集成，未公开发布。若对外分发包含此组件的组合 APK，须满足 GPL 对组合程序及对应源码的要求，不能将它当作仅需署名的宽松许可，也不能宣称根项目已另行换证。参考 GNU 官方 FAQ：https://www.gnu.org/licenses/gpl-faq.html 。

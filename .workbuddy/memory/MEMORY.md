# 项目：离线记账软件（OfflineLedger）

纯本地、不联网的 Android 记账 App。自动抓取支付宝 / 微信支付记录并归类。

## 项目定位
- **分发方式：纯自用 + 侧载安装**（用户已确认）→ 无障碍权限可自由使用，不受商店审核约束
- 三大约束（v1.1 修订，2026-09-15）：**数据永不出户**（可选受控联网——offline 版零 INTERNET / plus 版仅规则单向下载，用户已放宽"绝对离线"，真实诉求是防偷偷上传）、**极小体积**（APK ≤ 2.5 MB）、**本地加密**
- 包名 `com.ledger.offline`，minSdk 26 / targetSdk 34，Kotlin + XML View
- **总体方案 v1.2（定稿候选）**：`docs/方案设计.md`（2026-09-15：双构建版本 + 规则在线更新五道闸门 + **合并回填**[v1.2 核心，§4.3] + M0 编译里程碑 + 量化验收 + P3/P4 冻结纪律；审计 7 条全吸收，两大天花板已确认为需求基线；剩余待拍板：体积/微信服务号/通知商户名/规则仓库形态/主用版本）；另有 `docs/开源方案调研.md`（2026-09-15，不 fork 自研、抄 MIT/Apache 零件）

## 数据获取策略（2026-09-14 定稿，与早期设想不同）

**xlsx 导入是主力与地基，通知监听是日常自动补充，无障碍是可选开关（默认关）。**

- 用户已实测：**微信、支付宝导出的账单均为 .xlsx**（不是 csv）
- xlsx 是唯一 100% 完整且带收款方/收支方向的数据源
- 微信导出有两条接收通道：服务号（免解压码）/ 邮箱（加密 zip + 解压码）；单次最长 1 年
- 申请导出这一步本身需要联网（在 App 之外，用户手动完成）——这是与"绝对离线"初衷的张力点，已向用户明示
- 实施顺序：P0 导入闭环 → P1 通知采集 → P2 修正记忆 → P3 备份导出/无障碍

## 不可违背的项目约定

1. **禁止引入任何第三方运行时依赖**
   - 不用 Room / Hilt / Koin / Material Components / Retrofit / OkHttp / 任何统计或崩溃 SDK
   - 原因：它们会通过 manifest 合并偷偷带进 INTERNET 权限，或显著膨胀体积
   - 目前只允许：androidx.core-ktx、androidx.appcompat、androidx.recyclerview、androidx.constraintlayout

2. **联网边界（v1.1 修订）**：`offline` flavor 不声明 INTERNET（构建后跑 `tools/verify_offline.sh`）；
   `plus` flavor 声明 INTERNET，但网络代码**全部收敛在 `net/RuleUpdater.kt` 单文件**
   （系统 HttpURLConnection、域名白名单硬编码、只 GET、手动触发、无设备标识），
   构建后跑 `tools/verify_network_safety.py`。依然禁止云同步/云备份/第三方 SDK/统计上报

3. **解析规则必须外置为 JSON，不得硬编码进 Kotlin**
   - 微信 / 支付宝改版频繁，硬编码意味着每次都要重新出包
   - 加载优先级：`filesDir/parser_rules.json` > `assets/parser_rules.json`

4. **不做 AI 分类模型**
   - TFLite 文本分类模型体积 5~30 MB，是本 App 全部体积的十倍以上，且准确率不如
     「关键词规则 + 用户修正记忆」。这是经过评估的结论，不要重新引入。

5. **数据一律字段级加密**
   - 用 `FieldCipher`（AndroidKeyStore + AES-GCM），不用 SQLCipher（省 2~3 MB native .so）
   - 需要索引的字段存 HMAC 摘要（`amount_hash` / `merchant_hash`），不存明文
   - 密文以 **BLOB** 存储，禁止 Base64（否则多膨胀 33%）

6. **xlsx 解析禁止引入 Apache POI**（10MB+ JVM 库，Android 跑不动）
   - 用系统自带 `java.util.zip` + 流式 XML 解析器手写最小读取器
   - 表头自动定位（文件开头有说明行，真实表头在中间）；按表头名取列，绝不按列号

## 双路融合设计（v1.2 核心原则）
- xlsx 导入对既有通知记录**先合并、后判重**，四级匹配：单号精确 → 宽松指纹（金额+方向+±3min，
  仅限商户为空的通知记录）→ 完整指纹 → 新增
- 回填商户名/交易单号/官方分类/对账标记；**回填只补空字段，永不覆盖用户修正**
- 通知副本永远不覆盖 xlsx 记录（信息量少的一方让位）
- 支付宝「交易分类」列作分类种子；微信「交易类型」不是支出类别，仅辅助信号

## 解析器设计原则：宁缺毋滥
解析不出金额、或判断不了收支方向时**直接丢弃并计数**，不猜默认值。
错误的账目比缺失的账目更糟——用户一旦发现账本不准就会弃用。

## 修改解析规则的固定流程
1. 改 `app/src/main/assets/parser_rules.json`
2. 跑 `python tools/verify_parser_rules.py`（复刻了 Kotlin 端的判定逻辑，含负例断言）
3. 通过后再同步修改 `app/src/main/java/com/ledger/offline/parse/TransactionParser.kt` 与单测
4. 最后跑 `./gradlew :app:testDebugUnitTest`

## 已知天花板（不是 bug，不要试图"修复"）
- 微信 / 支付宝的通知**经常不带收款方**，只能标「未识别商户」，靠用户长按修正 → 记忆
- 通知监听覆盖率约 70~80%；账目完整性 100% 靠 xlsx 导入补齐
- 无障碍读屏命中率低（对方大量自绘控件），且微信可能风控，默认关闭
- 国产 ROM 会杀后台服务，需引导用户加白名单
- 通知使用权是**全量**的（系统不提供按 App 过滤），唯一对冲是零 INTERNET

## 工程位置（2026-09-16 起）
**`E:\AI\OfflineLedger`** —— 用户要求项目不放 C 盘。该路径是纯 ASCII，
因此构建不再有任何中文路径问题。C 盘旧副本（`C:\Users\zongb\WorkBuddy\记账软件`）保留未删。
**注意：WorkBuddy 会话工作区仍是 C 盘那个目录（记忆写在那里），改代码一律改 E 盘那份。**

## 构建环境（2026-09-16 M0 后更新）
**本机已具备无头构建能力**，不再依赖 Android Studio：
- 工具链在 `~/.workbuddy/toolchains/`（C 盘）：JDK 17.0.20.1、Gradle 8.7、Android SDK 34（platform-34 + build-tools 34.0.0）
- **构建走 `bash tools/build.sh [任务]`**（原地构建，无镜像）
- Gradle JVM 在沙箱内无网络，构建命令需沙箱外执行
- M0 验收：编译零错误、40 单测全绿、verify 全通过、release APK **632 KB**（新增 xlsx 解析器后需复核）、双 APK 无 INTERNET
- 产物在 `dist/`：`app-debug.apk`（可侧载）、`app-release-unsigned.apk`（待签名）

## P0 导入闭环进展（2026-09-16）
- ✅ `parse/xlsx/XlsxReader.kt`（系统 zip + SAX，纯 JVM 可单测，含 Excel 序列号→epoch 转换）
- ✅ `capture/BillImporter.kt`（xlsx/csv 分流、表头自动定位、按名取列、档案匹配、分类计数）
- ✅ `assets/parser_rules.json` v4 新增 `importProfiles`：微信侧**已用真实样本验证**；支付宝侧**待校准**（用户那份 zip 有密码）
- ⏳ **合并回填（§4.3 四级匹配）尚未实现** —— P0 剩余最后一块
- 真实样本在 `samples/`（已加 .gitignore，个人账单不进版本库）

## 待用户确认的开放问题（方案 §11）
1. ~~体积取舍~~ **已关闭（2026-09-16）：保 appcompat，release 实测 632 KB**
2. 微信服务号通道的 xlsx 能否保存到手机文件夹（决定导入是否纯手机闭环）
3. 用户支付通知是否带商户名（决定自动分类上限）
4. ~~是否做本地备份导出~~ 已确认做（P3）
5. 规则仓库形态（建议自建 GitHub 私有仓库）
6. 日常装 offline 还是 plus 版（建议日常 plus）
7. **支付宝账单 zip 的解压密码在用户手上**——需用户自行解压后提供 csv/xlsx 才能完成支付宝侧校准

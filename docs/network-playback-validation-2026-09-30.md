# 网络、播放与字幕验证（2026-09-30）

## 环境与结论边界

本机 Android API 36 模拟器，1920×1080；使用独立包名 `com.xxxx.emby_tv.latencytest`，保留原应用数据。宿主机显式 HTTP 代理 127.0.0.1:10808，模拟器通过 10.0.2.2:10808 访问同一代理。宿主机 TUN 已开启，不能把未指定代理的请求称为物理直连。模拟器系统 HTTP 代理为 null。代理路由国内可直连、国外走代理；所有对照请求均显式使用代理入口。

模拟器只能验证界面、网络和支持的解码格式；不能替代真实电视的 4K HEVC/HDR、杜比视界或音频输出验证。测试请求的下载目标也不是 Emby 实际片源 CDN。

## 网络对照

网络探针使用与 App 相同版本 OkHttp 5.0.0-alpha.14，关闭缓存、重定向和连接重试。每阶段每端国内/国外各 8 次 HTTPS HEAD，下载各 4 次 10 MB，宿主机与模拟器交错测量。下载目标为 Cloudflare speedtest `__down`。播放阶段同时播放约 8.1 Mbps 的 1080p 视频，下载探针会额外争用带宽。

| 阶段 | 宿主机成功下载 Mbps 中位数（范围） | 模拟器成功下载 Mbps 中位数（范围） |
| --- | --- | --- |
| App 关闭，测试前 | 16.68（7.66–22.29） | 10.67（5.52–20.13） |
| App 前台空闲 | 9.62（6.50–13.66） | 12.10（9.46–14.36） |
| App 播放中 | 14.29（9.24–17.28） | 12.92（12.32–13.87） |
| App 关闭，测试后 | 10.14（8.51–11.78，仅 2 次成功） | 8.28（5.88–12.50，仅 3 次成功） |

这里 Mbps 为完整下载平均速度，包含首包等待；不是物理链路带宽。

- 128 次延迟请求全部成功。国内中位数约 77–586 ms；国外约 270–1766 ms，随阶段和端点变化。
- 32 次下载中 29 次完整成功，共 290 MB 成功样本；另一次宿主机读取超时（已经收到约 13.5 KB），最后宿主机和模拟器各一次 HTTP 429。429 为测速站限流，不计入网络吞吐速度。
- App 关闭的初始阶段，模拟器仍出现最长 9.389 秒无正文数据的间隔；关闭后的阶段仍出现读取超时。可确认抖动在 App 未运行时就存在，不能将全部抖动归因 App。
- 模拟器到宿主机 10.0.2.2 ping 20 次，0% 丢包，平均 0.166 ms。本地模拟器桥接在此短样本中未见异常；不能据此定位公网、代理出口、测速站或 Wi-Fi 哪一跳导致停顿。
- 现有样本不足以保证稳定 4K。播放期间延迟升高与同时下载争用带宽相符，但不是 App 改坏系统网络的证据。没有持续数小时或真实 4K 电视压力测试。

原始非敏感数据及汇总位于 `/tmp/emby-network-check/samples.jsonl` 和 `summary.json`；临时目录不提交。

## 播放、遥控与菜单

实际播放：

- 《无神世界中的神明活动》S1E5、通过选集切至 E6：1080p H264、约 8.1 Mbps，正常出画面；暂停/恢复、短快进/快退、长按快退、选集、返回均已操作。
- 《遭到流放的转生重骑士凭借游戏知识大开无双》S1E1：1080p H264、约 8.2 Mbps，正常出画面，提供简体、繁体和法语字幕轨道。反复大幅寻址时观察到缓冲等待；不将“能出画面”视为持续无卡顿通过。
- 《牧神记》S1E100：3840×2160 HEVC Main10 HDR10、约 13.9 Mbps；模拟器解码失败。只读核对当前账号策略确认视频转码、音频转码、封装转换全部关闭。H264 诊断 profile 返回 `SupportsTranscoding=false`、无 TranscodingUrl；明确禁用 DirectPlay/DirectStream 的诊断请求也没有可播地址。没有改服务器权限。修复包实测只触发一次解码失败，随后记录“转码回退失败：没有找到转码URL”，不再假报成功并重放原片。

已确认并修复：

1. 进度条原来仅在 `isShowInfo && !isPlaying` 显示，寻址结束恢复播放后立即消失。现在播放中也可显示：第一下 Down 显示进度，第二下打开菜单；普通操作后 4 秒隐藏，暂停和长按寻址时保留。
2. 缓冲过程中 `isPlaying=false`，OK 原来无法暂停；按 `playWhenReady` 判断用户的播放意图后可暂停。
3. 解码失败的转码回退原来在没有 TranscodingUrl 时重用 DirectStreamUrl，错误记录“转码成功”并再次解码失败。现在回退只接受真正的转码 URL，没有则提示用户检查转码权限或兼容片源。
4. 暂停时关闭字幕可留下旧 cue；字幕显示层现在在关闭状态直接提交空 cue。
5. 在线字幕空结果/错误提示可聚焦，从而支持遥控滚动到完整可见。

遥控回归脚本 `/tmp/check-player-progress.py` 六项检查通过；长按 7 秒快退，在第 4 秒后及松开后进度仍可见，两项通过。菜单占约 38% 屏高，选集、字幕轨道、缓冲设置的底部内容可滚动到达；仅检查 1080p 默认字体。

## 字幕验证

轨道切换、关闭、在线字幕入口纳入核心播放范围。最新包在 05:19 暂停画面实测繁体中文字幕可见；同一画面关闭后字幕立即消失。已操作简体/繁体轨道选择，但没有逐句核对两条字幕的翻译差异；在线中文搜索实际返回空列表，因此没有验证下载和自动切换成功。空结果提示原先被底部裁切；提示与错误文字改为可聚焦，最新包按 Down 后提示范围为 y=1026–1064，整行处于 1080p 屏幕内。在线字幕依赖 Emby 服务器字幕提供商和下载权限；App 本身不凭空生成字幕。未验证 PGS/复杂 ASS 特效、全部语言或真实电视字幕渲染。

## 构建与检查

```sh
JAVA_HOME=/home/cloud/Documents/jdk-21.0.9 ANDROID_HOME=/home/cloud/Android/Sdk ./gradlew -I /tmp/emby-latency-test.init.gradle :app:assembleDebug --no-daemon
JAVA_HOME=/home/cloud/Documents/jdk-21.0.9 ANDROID_HOME=/home/cloud/Android/Sdk ./gradlew :app:assembleDebug --no-daemon
JAVA_HOME=/home/cloud/Documents/jdk-21.0.9 /home/cloud/Documents/android-studio/plugins/Kotlin/kotlinc/bin/kotlinc app/src/main/java/com/xxxx/emby_tv/util/PlaybackRequest.kt app/src/main/java/com/xxxx/emby_tv/util/NetworkRetryPolicy.kt app/src/test/java/com/xxxx/emby_tv/PlaybackRegressionChecks.kt -include-runtime -d /tmp/emby-network-check/playback-regression.jar
/home/cloud/Documents/jdk-21.0.9/bin/java -jar /tmp/emby-network-check/playback-regression.jar
git diff --check
node /home/cloud/.codex/skills/itms-risk-scan/scripts/scan-itms-risk.mjs --workspace /home/cloud/Documents/embyinall/openemby_tv --scope xj-itms-new --output-dir /tmp/emby-network-check/risk-scan
```

两个 APK 构建均成功；独立播放/网络逻辑 27 项检查通过；diff 格式检查通过。风险扫描仅覆盖两个改动 XML、两行新增，报告零风险；扫描器不覆盖 Kotlin，不能宣称 Kotlin 风险已通过扫描。模拟器包另存 `/tmp/emby-network-check/openemby-emulator-debug.apk`，常规包在 `app/build/outputs/apk/debug/app-debug.apk`。

最新测试进程 PID 10286 的 `adb logcat -b crash -d --pid=10286` 读取为空。测试结束返回首页。没有通过此检查证明所有历史进程或全部设备均不会崩溃。

改动保持未提交、未推送。

## 补充：进度条下移与字幕空结果定位

- 底部进度面板留白由 32dp 改为 8dp，菜单提示移至时间和进度线之前。1080p 实测进度线从约 y=887 下移至 y=1000–1008，底部保留约 72px 安全空间。更新后六项遥控回归再次全部通过；隔离包与常规包构建均成功。
- 只读账号策略确认 `EnableSubtitleDownloading=false`、`EnableSubtitleManagement=false`、`IsAdministrator=false`。三个视频分别使用 chi、zho、eng 并携带有效 MediaSourceId 请求搜索，九次都收到空数组；因此不是 App 列表展示丢失了服务器返回结果。
- 插件列表只读请求返回 HTTP 403，当前无法核对提供商安装/配置；不能认定空列表仅由关闭下载权限导致。要恢复服务端字幕搜索/下载，需要管理员核对权限和字幕源，或采用客户端独立字幕服务。后续按用户确认的客户端 ASSRT 路线实现，见下文。
- 本次 `git diff --check` 通过；风险扫描仍仅覆盖两个 XML、两行新增，零发现，Kotlin 不在扫描覆盖范围。

## ASSRT 手动外挂字幕接入

按用户确认的路线，在线字幕页改为客户端 ASSRT 搜索：默认只展示片名，必须由用户按搜索按钮才请求；结果先展开文件列表，用户选具体 SRT/ASS/SSA/VTT 文件才下载、加载。可编辑关键词以缩短片名或使用别名。来源说明和 assrt.net 链接置于页面中。原生/服务器字幕保持现有默认选择逻辑；可在字幕页关闭外挂或切回原轨道。

请求使用 App 当前代理，Token 仅从 Git 忽略的 local.properties 或 ASSRT_TOKEN 环境变量注入 APK。API 请求使用 Bearer 头，文件下载不附带 ASSRT/Emby 凭据。文件最大 5 MiB，缓存最多 20 项；超大文件、压缩包、HTML 错误页拒绝加载，UTF-8/GB18030 转为 UTF-8。单次请求总时限 45 秒，离开页面取消请求。ASSRT 操作没有调用 Emby 字幕搜索、下载、上传或媒体刷新接口；客户端轨道索引 -2 在正常会话进度上报中映射为关闭字幕 -1，避免污染服务器轨道索引。加载本地字幕重建 MediaItem 时保留当前进度和播放/暂停意图。

验证命令及结果：

- kotlinc 编译 AssrtSubtitles.kt 与 AssrtSubtitleChecks.kt，java 运行 `com.xxxx.emby_tv.AssrtSubtitleChecksKt`：15 项通过（JSON、API 错误、文件选择、编码、文件大小和内容校验）。
- `./gradlew -I /tmp/emby-assrt-instrument.init.gradle :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon`：构建通过。测试 runner 为 LocalSubtitleInstrumentation，使用独立包和本地生成的 6 秒视频、SRT、ASS 素材，不依赖 Emby 账号。
- `adb shell am instrument -w com.xxxx.emby_tv.latencytest.test/com.xxxx.emby_tv.LocalSubtitleInstrumentation`：返回 `PASS: local SRT and ASS cues, pause, disable, re-enable, seek` / `INSTRUMENTATION_CODE: -1`。确认两种字幕产生预期 cue、暂停状态、关闭参数；恢复和寻址执行成功，不代表已逐句检查寻址后的同步或 ASS 高级特效。第一次设备测试缺少 onCreate/start 入口，未开始执行而等待；补齐后再次执行通过。
- 模拟器遥控触发 ASSRT 搜索、失败后重试，错误文字完整可见 y=963–1001，App 未退出；随后进度条六项回归再次全部通过。
- 用户换网络后代理监听 0.0.0.0:10808；宿主机仍通过 127.0.0.1:10808，模拟器应使用 10.0.2.2:10808。百度和 Google 两次对照 HTTPS 请求均 200；api.assrt.net、secure.assrt.net 在 TLS 握手阶段 EOF，电脑物理网卡直连测试也被重置。官方备用 api.makedie.me 的证书主机名不匹配。没有跳过证书校验或把 Token 改走明文 HTTP。

后续定位与真实流程验证：

- Google/Cloudflare HTTPS DNS 均将 api.assrt.net 解析至 91.234.199.193；本机及代理 Rule 模式查询却返回 43.133.211.58。临时 Global 模式下核心 DNS 变为 91.234.199.193，带预置 Token 的 API 返回 status=0；恢复 Rule 后又握手失败。因此可以确认失败随本地 DNS/分流模式变化，不能归因为 App 地址、Token 无效或 ASSRT 全站故障；没有证明错误解析来自缓存、运营商劫持或具体某一条规则。
- 临时模式测试均保存原模式，脚本 finally 恢复 Rule；模拟器长流程另有最多四分钟自动恢复保护。没有修改长期节点或 DNS/路由文件。尝试代理 DNS 缓存刷新接口返回 HTTP 404，当前 sing-box 1.12.22 不支持该接口；通过切换模式的短暂诊断继续验证。
- App 内主动搜索 Breaking Bad 成功；展开“绝命毒师 第二季”资源包，选择具体 S02E02 ASS 文件下载。缓存文件 716505 字节、494 条 Dialogue；没有提交下载字幕或账号资料。
- 加载后仍暂停在 16:17，等待准备完成后 Paused 状态可见；setMediaItem 日志为 977548 ms，与暂停画面进度对应，没有回到开头。恢复播放后，20:47 画面中的外挂文字与实际缓存 ASS 的 20:46.94–20:48.30 cue 一致，证明真实文件进入渲染路径。该文件属于验证样本，与当前动画不同，不能据此宣称自动匹配准确或字幕与该动画同步。
- 字幕列表可滚动至末尾 ASSRT 轨道，长文件名完整可见（1080p y=961–1049）；实际切回原简体轨道，再关闭，暂停画面旧 cue 清除。为长标题保留勾选图标空间，Text 增加 weight(1f)；最后这一排版微调已编译，未再次下载样本复验选中图标。
- 请求期间保留 TV 按钮焦点、以 busy 拦截重复请求；列表条目增加稳定 key，结果转文件列表前将焦点放回搜索按钮。修复后能通过遥控进入结果和文件列表，未再跳回 Info 标签。
- 最新运行进程 PID 3935 的 crash buffer 为空。日志存在原片内置 ASS 的 SsaParser layer 警告，未据此宣称全部 ASS 样式完美支持。

边界：真实在线搜索、文件展开、下载、附加、暂停保留、外挂文字渲染和字幕关闭已验证；样本不用于验证自动片名匹配和内容同步。标准 SRT/ASS 独立设备样本通过，未真实下载验证 SSA/VTT、UTF-16 文件、PGS、复杂 ASS 特效或真实 4K 电视。当前 Rule 模式的 ASSRT DNS/分流问题仍存在；使用时需在代理中将 ASSRT 域名配置为可用代理路由与远程 DNS。


最终风险扫描：未提交范围覆盖 3 个文件、96 行新增（两个字符串 XML 和一份 Java 设备测试），零发现；主要生产 Kotlin 和 Gradle Kotlin 脚本不在扫描器覆盖范围。git diff --check 通过。最终常规 APK 位于 app/build/outputs/apk/debug/app-debug.apk，隔离包另存 /tmp/emby-network-check/openemby-assrt-emulator-debug.apk；隔离包包含真实流程复验版本，最终长标题图标空间/加载文案微调仅在常规包中。改动未提交、未推送。

# 2026-09-30 播放与代理体验修复验证

## 已修改

- 图片临时网络错误：最多 3 次重试，间隔 1 / 3 / 8 秒；404、认证和证书错误不循环重试，保留成功图片缓存。
- 播放：兼容绝对 URL、已有 `/emby` 前缀和反代路径；按服务端标志补 token；使用 RequiredHttpHeaders；转码回退优先 TranscodingUrl。
- 媒体流网络失败：最多 3 次重新 prepare 同一源，不用转码处理网络断线；直链 404/410 可触发一次服务器转码回退；错误/空播放信息结束缓冲指示。
- 菜单：65% → 38% 屏幕高度；收紧页签间距；选集图片按剩余高度计算，纵向滚动兜底；简介不再限制 10 行。其余内容页沿用现有滚动容器。
- 测速：测试当前填写的代理，无需保存；使用独立 client、禁用 HTTP cache；报告 Emby 响应耗时与海报样本下载 Mbps，最多读取 2 MiB；显示目标走代理或局域网直连；离页/改配置取消测速请求并释放资源。

## 实际执行的离线检查

使用本机 JDK 21、Gradle 自带 Kotlin 2.0.21 编译器，以及本机现有 Gson 2.10.1 / OkHttp 4.9.3 / Okio 2.8.0 / Coroutines 1.6.4。只验证独立逻辑，不代表 Android 工程依赖组合编译通过。

```sh
task_java=/home/cloud/Documents/jdk-21.0.9/bin/java
task_kotlin_lib=/home/cloud/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/lib
task_cp="$task_kotlin_lib/kotlin-stdlib-2.0.21.jar:$task_kotlin_lib/kotlinx-coroutines-core-jvm-1.6.4.jar:/home/cloud/Documents/maven-repo/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar:/home/cloud/Documents/maven-repo/com/squareup/okhttp3/okhttp/4.9.3/okhttp-4.9.3.jar:/home/cloud/Documents/maven-repo/com/squareup/okio/okio/2.8.0/okio-2.8.0.jar"
"$task_java" -cp "$task_kotlin_lib/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -classpath "$task_cp" -d /tmp/openemby-checks/all-checks.jar \
  app/src/main/java/com/xxxx/emby_tv/util/PlaybackRequest.kt \
  app/src/main/java/com/xxxx/emby_tv/util/NetworkRetryPolicy.kt \
  app/src/main/java/com/xxxx/emby_tv/data/remote/ProxySpeedTest.kt \
  app/src/test/java/com/xxxx/emby_tv/PlaybackRegressionChecks.kt \
  app/src/test/java/com/xxxx/emby_tv/ProxySpeedRegressionChecks.kt
"$task_java" -cp "/tmp/openemby-checks/all-checks.jar:$task_cp" com.xxxx.emby_tv.PlaybackRegressionChecksKt
"$task_java" -cp "/tmp/openemby-checks/all-checks.jar:$task_cp" com.xxxx.emby_tv.ProxySpeedRegressionChecksKt
git diff --check
node /home/cloud/.codex/skills/itms-risk-scan/scripts/scan-itms-risk.mjs \
  --workspace /home/cloud/Documents/embyinall/openemby_tv --scope xj-itms-new \
  --output-dir /tmp/openemby-checks/risk-scan
```

结果：27 项播放/重试逻辑检查通过；测速样本上限、请求头、路由标识、空库、HTTP 407 检查通过。测速测试通过拦截器返回模拟响应，没有连接真实服务器。这些是手动执行的独立检查入口，没有配置成 Gradle/JUnit 自动测试。

修复前用旧的 URL 拼接逻辑执行相同播放检查，失败：`Expected https://cdn.test/movie.mkv?sig=abc, got https://emby.test/embyhttps://cdn.test/movie.mkv?sig=abc`。

风险扫描原文：`变更文件 2，新增行 28，未跟踪文件 0，风险 0，P0 0，P1 0，P2 0，P3 0`。扫描器只支持其配置的源码类型，本次只覆盖两个 XML，未覆盖 Kotlin。`xj-itms-new` 是扫描器固定根仓库别名，实际 workspace 已指定此项目。

## 未验证

- 完整 Android 编译、APK 安装和运行未完成。首次 wrapper 下载被沙箱阻止：`java.net.SocketException: Operation not permitted`；下载批准被拒绝。
- 使用现有 Gradle 的离线构建命令 `gradle --offline --no-daemon -g /tmp/openemby-gradle-check :app:compileDebugKotlin :app:assembleDebug` 也被沙箱本机锁服务限制：`Could not determine a usable wildcard IP for this machine.`；离线构建批准被拒绝。
- 未连接真实 Emby、代理、数据库；未确认用户昨天的具体失败原因。
- 图片重试耗尽后仍会停在失败状态，长时间断网后需要重新进入页面。图片缓存内容陈旧是否存在，尚无服务端响应证据。
- 菜单代码保留滚动与尺寸兜底，720p/1080p、大字体和遥控焦点的视觉效果仍需运行检查。
- Android TV 模拟器可检查界面/网络；电视真实硬解、杜比视界和音频输出必须在设备上复验。

所有修改保持未提交。

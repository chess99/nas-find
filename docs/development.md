# 开发与测试

当前用户行为见[使用说明](usage.md)，运行配置见[部署文档](deployment.md)，文档分类见[文档目录](README.md)。历史记录用于解释设计过程，不替代当前代码。

## 架构与源码入口

```text
Linux 本地目录 → inotify 变化通知 → plocate 索引
                                     ↓
                             查询快照与 HTTP API
                              ↙             ↘
                          网页界面      Windows / macOS 客户端 → SMB / 系统应用
```

| 目录 / 文件 | 职责 |
|---|---|
| `nasfind/config.py` | 服务配置、索引范围和文件访问边界 |
| `nasfind/watcher.py` | inotify 监听、目录变化与恢复 |
| `nasfind/engine.py` | 索引调度、发布和旧快照保护 |
| `nasfind/queries.py` | 分类筛选、临时 SQLite 查询快照和批量路径读取 |
| `nasfind/web.py` | HTTP、登录、文件预览、下载和查询接口 |
| `nasfind/static/` | 网页入口及两端共用的列表、选择逻辑和样式 |
| `desktop/src/` | 桌面页面与 Tauri 适配层 |
| `desktop/src-tauri/src/` | 连接、平台路径、系统操作和批量导出 |
| `android/app/src/main/` | Android 原生界面、会话存储、查询、预览和文件操作 |
| `android/app/src/test/`、`android/app/src/androidTest/` | Android 协议/模型测试、设备界面测试与显式真实服务检查 |
| `desktop/sync.mjs` | 构建前同步共用前端文件 |
| `tests/`、`desktop/tests/` | 服务端集成测试和前端逻辑测试 |

共用实现是 `nasfind/static/explorer.js`、`selection.js` 和 `explorer.css`。不要直接修改生成的 `desktop/src/shared/` 副本。

## 需要保持的行为

- 搜索和选择只读取索引，不为每一行访问源文件、读取缩略图或检查文件是否存在。
- 索引更新失败或数据盘不可用时保留旧索引，新版本校验完成后原子发布。
- 查询固定索引版本，分类与目录过滤在分页之前完成。全选包括未加载结果；分页不能改变选择含义。
- 不完整、过期或失败的查询不能冒充完整导出；取消不能发布半份清单。
- 桌面路径从已验证的配置与相对路径构造；macOS 打开前检查实际 SMB 挂载来源与文件边界。系统操作使用结构化参数，不能拼接任意命令。
- 原生打开、批量文本复制和文件复制是不同操作，不能静默相互替代。

查询快照使用系统 SSD 上的临时 SQLite 文件，不依赖独立数据库服务。缓存、页大小和任务额度以 `queries.py` 为准；更改这些额度时同步更新相关文档。

## Docker 构建与发布验证

Docker 部署步骤见[部署文档](deployment.md)。镜像以 Ubuntu 24.04 为基础，包含服务端、容器入口和许可证；构建上下文由 `.dockerignore` 的允许列表控制。

在原生 Linux Docker 主机上执行：

```sh
docker build -t nas-find:local .
docker run --rm --user 1000:1000 --entrypoint python3 \
  -e PLOCATE_BIN=/usr/bin/plocate -e UPDATEDB_BIN=/usr/sbin/updatedb.plocate \
  --mount type=bind,src="$PWD",dst=/workspace,readonly -w /workspace \
  nas-find:local -m unittest discover -s tests -v
python3 docker/smoke.py nas-find:local
docker compose --env-file examples/docker.env config --quiet
```

集成测试使用真实 plocate / inotify。容器验收脚本使用临时合成数据，覆盖登录、搜索、排除项、非 root 进程、只读源目录、创建/改名/删除通知、空闲不更新、重启后的密码与索引保留、健康检查和优雅停止。脚本要求原生 Linux Docker 主机，以测试本地目录的 inotify 事件。

GitHub 的 `Docker` 工作流在相关 PR/main 改动时构建并验证 AMD64 镜像；发布 `v*` 标签或手动运行工作流时，在测试通过后构建并推送 AMD64 / ARM64 镜像到 GHCR。手动 main 发布及不含 `-` 的稳定版本标签更新 `latest`；预发布标签与其他分支的手动构建只生成版本/提交标签。建议用户部署固定版本，维护者应按版本顺序发布稳定标签，避免旧标签覆盖 latest。

## 客户端 Release

`Client Release` 工作流构建两个安装包：

| 系统 | 构建目标 | 安装包 |
|---|---|---|
| Windows 64 位（Intel / AMD） | `x86_64-pc-windows-msvc` | `NAS-Find_<版本>_windows-x64-setup.exe` |
| macOS（Apple M 系列） | `aarch64-apple-darwin` | `NAS-Find_<版本>_macos-arm64.dmg` |

在 GitHub 的 Actions 页选择 `Client Release`，点击 Run workflow 并选择分支，可构建测试包。完成后从该次运行的 Artifacts 下载 `client-windows-x64` 或 `client-macos-arm64`。

正式发布时，将 `desktop/package.json`、`desktop/package-lock.json`、`desktop/src-tauri/tauri.conf.json`、`desktop/src-tauri/Cargo.toml` 和 `desktop/src-tauri/Cargo.lock` 中的客户端版本更新为同一个版本。锁文件只更新本项目包的版本；依赖升级单独进行。检查版本并运行发布脚本测试：

```sh
node scripts/release.mjs validate
node --test scripts/release.test.mjs
```

提交版本改动后，创建并推送对应的 `v<版本号>` 标签。例如客户端版本为 `0.4.2` 时：

```sh
git tag -a v0.4.2 -m "NAS Find 0.4.2"
git push origin main
git push origin v0.4.2
```

标签会同时触发客户端 Release 和 Docker 镜像工作流。客户端工作流检查标签与版本文件一致，运行前端与原生测试，再构建安装包。两个平台均成功后，将安装包、`LICENSE` 和 `SHA256SUMS.txt` 上传至 Release，生成下载表格与更新说明，然后发布。

带 `-` 的版本（例如 `0.4.3-rc.1`）发布为 Prerelease；稳定版本设为 Latest。发布失败留下的草稿可通过重新运行工作流继续上传；已经公开的版本保持不变，修改安装包时使用新版本号。工作流使用仓库自带的 `GITHUB_TOKEN`，只在上传 Release 的任务中申请写权限。

## Windows 构建

准备 Node.js LTS、Rust stable、Microsoft C++ 构建工具和 Windows SDK。最终用户运行客户端需要 WebView2，不需要开发工具链。

从仓库根目录进入桌面工程：

```powershell
cd desktop
npm ci
npm test
.\build.ps1 test
.\build.ps1 build
```

`build.ps1` 可使用已安装的 Microsoft 工具链，也支持被忽略的 `.local/msvc` 私有工具目录；私有目录不是仓库的一部分。构建产物在 `desktop/src-tauri/target/release/`，NSIS 安装包在其中的 `bundle/nsis/`。

构建后可运行 `install.ps1` 按当前用户安装；可用 `-InstallDirectory` 指定自己的目录。更新正在运行的客户端前先退出旧实例。安装脚本的 `-ImportExistingConnection` 仅用于显式导入已有本地凭据文件，不是新用户必须执行的步骤。

## Android 构建与安装

准备 JDK 17、Android SDK Platform 34 和 Build Tools 34.0.0。Android 工程独立位于 `android/`，使用 Kotlin、Jetpack Compose 和 Gradle Wrapper；可直接在 Android Studio 打开该目录。最低支持 Android 8.0（API 26）。

将 `JAVA_HOME` 指向自己的 JDK，将 `ANDROID_HOME` 指向自己的 SDK；也可在被忽略的 `android/local.properties` 中设置 `sdk.dir`。不要把机器路径或签名密码写入提交的文件。

Windows 从仓库根目录执行：

```powershell
cd android
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
adb devices -l
# 替换为明确选择的真机或模拟器序列号。
adb -s <设备序列号> install -r app/build/outputs/apk/debug/app-debug.apk
```

Linux / macOS 使用 `./gradlew`。调试 APK 在 `android/app/build/outputs/apk/debug/app-debug.apk`，包名 `net.chess99.nasfind.debug`，可与将来的正式包并存。部分厂商手机会要求在手机上确认 USB 安装；拒绝时不能当作安装成功，也不应关闭安装安全检查来绕过。

执行合成数据设备测试：

```powershell
cd android
$env:ANDROID_SERIAL = '<设备序列号>'
.\gradlew.bat connectedDebugAndroidTest
```

设备测试会使用测试 NAS 配置，改变调试包的本地会话、历史和临时文件。请使用测试设备或可重置的调试安装；它不修改 NAS 服务。默认真实 NAS 用例因没有显式提供连接文件而跳过。报告在 `android/app/build/reports/`。如需保留安装，可先安装两个 APK，再直接运行：

```powershell
adb -s <设备序列号> install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s <设备序列号> install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <设备序列号> shell am instrument -w -r -e class net.chess99.nasfind.ClientFlowTest net.chess99.nasfind.debug.test/androidx.test.runner.AndroidJUnitRunner
```

上面直接安装命令从仓库根目录执行。用例通过手机上的合成 HTTP 服务验证查询竞态、分页、多选、文本/图片/PDF、音频播放、旋转恢复、登录过期、加密会话、CSV 准备和系统保存/取消；测试 APK 内的独立接收应用还会验证 FileProvider 读取授权。测试数据和接收应用不进入客户端 APK。测试完成后可用 `adb -s <设备序列号> uninstall net.chess99.nasfind.debug.test` 移除测试包。

已有 NAS 的只读检查需显式提供被忽略的 JSON 文件，包含 `url` 和 `password`。先安装调试 APK 及测试 APK，再从仓库根目录运行：

```powershell
python scripts/android-smoke.py --serial <设备序列号> --access .local/<连接文件>.json
```

脚本通过 adb 标准输入把连接信息送入调试包私有目录，检查后删除输入文件，不把密码放进构建参数或输出。检查登录、索引、PDF 查询与适用的分页/选择后，会将会话加密保存在该调试客户端并打开应用。检查不刷新索引、不下载真实文件，也不更改 NAS 配置。

`Android` 工作流在相关 PR/main 改动时运行单元测试、Lint、构建调试 APK 和设备测试 APK；调试包通过 Actions 的 `nas-find-android-debug` artifact 下载。CI 不自动执行真机/模拟器测试，设备测试结果须单独记录。Android 尚未接入客户端正式 Release；调试包使用调试签名，不冒充正式签名发行包。`assembleRelease` 可构建未签名包，正式分发前需单独配置安全的签名流程。

Android 的登录会话由 Android Keystore 的 AES-GCM 密钥加密，且与服务地址绑定；密码不落盘，关闭云备份和设备迁移。应用只通过配置的服务根地址请求，认证 HTTP 客户端不跟随重定向、不使用系统代理。外部打开通过 FileProvider 仅暴露 `cache/outgoing/` 中的临时副本；保存通过系统文档选择器，不申请广泛文件访问权限。

## macOS 构建

准备 Node.js LTS（包含 npm）、Rust stable 和 Xcode Command Line Tools（`xcode-select --install`）。

```sh
cd desktop
npm ci
npm test
./build.sh test
./build.sh build
```

Tauri 自动合并 `src-tauri/tauri.macos.conf.json`。产物为 `src-tauri/target/release/bundle/macos/NAS Find.app` 和 `bundle/dmg/` 中的安装镜像；只构建应用可用 `npm run build -- --bundles app`。默认构建当前 CPU 架构；其他架构需对应 Rust target，本文不将本机验证视为 Intel 验证。

macOS 使用 `native_macos.rs`，Windows 原生模块和依赖按平台编译。Mac 以 POSIX 挂载路径打开文件，打开前用 `statfs` 校验 SMB 服务器、共享名和挂载位置；服务器别名需与挂载时一致。查询、复制路径和导出不探测源文件。虚拟列表将 WebKit 弹性滚动产生的越界 scrollTop 限制在有效范围，避免向 NAS 请求负数页。客户端直连搜索服务，不通过系统 HTTP/SOCKS 代理，避免局域网地址被代理转发。密码放在当前用户的 Keychain，设置文件只保存凭据引用；退出登录时删除对应凭据。

本地构建默认使用 ad-hoc 签名，没有 Apple 开发者签名与公证。公开分发前需配置签名及公证。直接在本机构建运行不需要开发者账号。需要 Finder 显示简介时，系统可能请求自动化权限。

在退出客户端后，可显式导入被忽略的连接文件：

```sh
"src-tauri/target/release/bundle/macos/NAS Find.app/Contents/MacOS/nas-find-desktop" --import-connection /absolute/path/to/access.json
```

连接文件的 `client_config` 在原有 `server`、`share` 字段外增加 `mount_path`（例如 `/Volumes/files`）；不要将实际账号、密码或地址打包到应用。

## 验证

在 Linux 仓库根目录，用实际安装的 plocate 和 updatedb 路径运行：

```sh
PLOCATE_BIN=/path/to/plocate \
UPDATEDB_BIN=/path/to/updatedb.plocate \
python3 -m unittest discover -s tests -v
```

两端前端测试执行 `npm test`，Windows Rust 测试执行 `build.ps1 test`，macOS 执行 `./build.sh test`。Mac 的忽略测试可用明确的 `NAS_FIND_ACCESS_FILE` 加 `cargo test --manifest-path src-tauri/Cargo.toml -- --ignored` 执行，包含真实 NAS/SMB 检查与可删除的钥匙串测试凭据。没有 Linux 工具环境时，Python 集成测试会跳过。

| 改动 | 重点验证 |
|---|---|
| 索引与监听 | 创建、移动、删除、无变化时不更新、溢出恢复、失败保留旧索引 |
| 查询与列表 | 名称 / 路径区别、分类、超过一页、末页、跨页选择、过期与会话隔离 |
| 批量操作 | 数量与顺序完整、特殊名称、大小上限、取消后没有半成品 |
| 原生操作 | 中文及空格路径、UNC / 映射盘 / SMB 挂载、挂载来源不匹配、单选与多选边界、错误提示 |
| 文档 | 相对链接、命令与当前代码是否一致、是否残留真实环境信息 |

真实部署验证使用明确授权的目标与自有测试数据。NAS 冒烟测试通过 `NAS_FIND_ACCESS_FILE` 读取私有连接文件中的 `client_config`；也可通过 `NAS_FIND_CLIENT_CONFIG` 指向独立的[客户端配置](../examples/client-config.json)。系统菜单测试另需 `NAS_MENU_TEST_PATH`，可用 `NAS_MENU_EXPECT_LABEL` 指定预期应用菜单文字，不假设已安装某个播放器。

## 配置与发布约定

各组件的环境配置入口：

| 位置 | 当前行为 |
|---|---|
| `nasfind/config.py` | 索引根目录与 UNC 根路径必填；默认只允许回环网段，特定目录排除默认为空，程序可按配置路径或 PATH 查找 |
| `desktop/src-tauri/src/config.rs` | 首次连接信息为空；已有保存配置照常读取，导入同一共享时保留映射盘偏好 |
| `deploy.py`、`scripts/deploy_remote.py` | SSH 目标与配置必填，检查已准备的依赖，执行测试和版本切换；默认保留现有有效配置，激活失败回滚 |
| 真实连接测试 | 从测试连接文件读取目标信息，可指定应出现的系统菜单 |

部署检查、首次配置、配置继承、显式更新和失败回滚由本地隔离测试覆盖。普通测试不连接真实 NAS，也不调用 systemd 修改宿主服务。

仓库内容按下面的边界维护：

| 进入公开仓库 | 只留在本地 |
|---|---|
| 程序源码、通用脚本、配置示例、服务模板 | 自己机器的实际部署配置、SSH 别名组合、一次性部署脚本 |
| 合成测试数据、经过整理的调研与验证记录 | 密码、私钥、访问令牌、私人文件清单与原始截图 |
| 确实需要维护的第三方源码补丁及原许可证 | 下载的软件包、编译工具、索引数据库、缓存、构建产物 |

配置示例放在 `examples/`，实际配置、个人脚本和下载的工具缓存放在被忽略的 `.local/`。需要随项目维护的第三方源码补丁放在 `vendor/`，并保留原许可证。

项目原创代码采用 [AGPL-3.0-only](../LICENSE)，第三方代码保留各自许可。发布构建产物时应附带许可说明并按要求提供对应源码；对外运行修改版网络服务时，应按 AGPL 提供源码获取入口。

发布前检查历史中的凭据和私人文件清单，公开示例使用通用路径和主机名。

不要提交 `.local/`、凭据、真实文件清单、日志、`node_modules/`、`target/` 或生成的共用前端副本。提交前检查暂存区，只包含本次修改；并行任务的改动保留原状。

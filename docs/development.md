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

两端前端测试执行 `npm test`，Windows Rust 测试执行 `build.ps1 test`，macOS 执行 `./build.sh test`。Mac 的忽略测试可用明确的 `NAS_FIND_ACCESS_FILE` 加 `cargo test --manifest-path src-tauri/Cargo.toml -- --ignored` 执行，包含真实 NAS/SMB 检查与可删除的钥匙串测试凭据。没有 Linux 工具环境时，Python 集成测试会跳过；这不代表集成测试已经通过。

| 改动 | 重点验证 |
|---|---|
| 索引与监听 | 创建、移动、删除、无变化时不更新、溢出恢复、失败保留旧索引 |
| 查询与列表 | 名称 / 路径区别、分类、超过一页、末页、跨页选择、过期与会话隔离 |
| 批量操作 | 数量与顺序完整、特殊名称、大小上限、取消后没有半成品 |
| 原生操作 | 中文及空格路径、UNC / 映射盘 / SMB 挂载、挂载来源不匹配、单选与多选边界、错误提示 |
| 文档 | 相对链接、命令与当前代码是否一致、是否残留真实环境信息 |

真实部署验证使用明确授权的目标与自有测试数据。NAS 冒烟测试通过 `NAS_FIND_ACCESS_FILE` 读取私有连接文件中的 `client_config`；也可通过 `NAS_FIND_CLIENT_CONFIG` 指向独立的[客户端配置](../examples/client-config.json)。系统菜单测试另需 `NAS_MENU_TEST_PATH`，可用 `NAS_MENU_EXPECT_LABEL` 指定预期应用菜单文字，不假设已安装某个播放器。

## 开源发布准备

项目维护程序、配置接口、示例文件和部署步骤。依赖安装、SSH 连接、目录选择可由用户或 agent 按目标环境完成；通用远程部署脚本是便利工具，不是运行服务的前提。

环境配置已从固定的开发机器默认值中分离：

| 位置 | 当前行为 |
|---|---|
| `nasfind/config.py` | 索引根目录与 UNC 根路径必填；默认只允许回环网段，特定目录排除默认为空，程序可按配置路径或 PATH 查找 |
| `desktop/src-tauri/src/config.rs` | 首次连接信息为空；已有保存配置照常读取，导入同一共享时保留映射盘偏好 |
| `deploy.py`、`scripts/deploy_remote.py` | SSH 目标与配置必填，检查已准备的依赖，执行测试和版本切换；默认保留现有有效配置，激活失败回滚 |
| 真实连接测试 | 明确读取测试连接文件，可指定应出现的系统菜单，不再借用开发者环境 |

部署检查、首次配置、配置继承、显式更新和失败回滚由本地隔离测试覆盖。普通测试不连接真实 NAS，也不调用 systemd 修改宿主服务。

仓库内容按下面的边界维护：

| 进入公开仓库 | 只留在本地 |
|---|---|
| 程序源码、通用脚本、配置示例、服务模板 | 自己机器的实际部署配置、SSH 别名组合、一次性部署脚本 |
| 合成测试数据、经过整理的调研与验证记录 | 密码、私钥、访问令牌、私人文件清单与原始截图 |
| 确实需要维护的第三方源码补丁及原许可证 | 下载的软件包、编译工具、索引数据库、缓存、构建产物 |

配置示例放在 `examples/`，实际配置和个人脚本可放在被忽略的 `.local/`。公开部署脚本只维护通用流程，不包含开发者的目标地址。源码里的 `vendor/` 补丁与本机下载的工具缓存不是一回事。

项目原创代码采用 [AGPL-3.0-only](../LICENSE)，第三方代码保留各自许可。发布构建产物时应附带许可说明并按要求提供对应源码；对外运行修改版网络服务时，应按 AGPL 提供源码获取入口。

发布前检查历史中的凭据和私人文件清单。普通用户名、文件数量或内网地址本身通常不需要专门重写历史；公开示例用通用值，主要是方便别人部署。

不要提交 `.local/`、凭据、真实文件清单、日志、`node_modules/`、`target/` 或生成的共用前端副本。提交前检查暂存区，只包含本次修改；并行任务的改动保留原状。

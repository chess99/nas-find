# 部署与维护

本文供人和 AI agent 共用。示例路径和主机名仅用于说明，应根据目标环境填写，不能把仓库默认值当作已经确认的部署信息。

## 先确认环境

| 信息 | 需要明确的内容 |
|---|---|
| NAS | 主机、Linux 发行版、CPU 架构、SSH 用户与授权范围 |
| 数据目录 | 实际本地路径、挂载点、文件读取权限、排除项 |
| 运行目录 | 代码、索引与查询缓存的存放位置，建议使用系统 SSD |
| 网络 | 监听地址、端口、允许访问的网段 |
| 文件访问 | UNC 共享根路径，以及 Windows 上可选的映射盘 / macOS 上实际的 SMB 挂载目录 |
| 已有部署 | 配置、密码、服务和索引是否已存在；更新时应保留哪些内容 |

可以通过只读检查获得的信息先检查；仍缺少的目标或凭据来源再向用户询问。单纯准备文档不需要连接机器、安装软件或重启服务。

## 部署方式

服务本身通过配置文件运行，不依赖远程部署脚本。项目提供[配置示例](../examples/server-config.json)和 [systemd 用户服务模板](../examples/nas-find.service)；用户或 AI agent 可按自己的发行版准备依赖、传输代码，再采用下面的启动方式。

`deploy.py` 是可选的通用 SSH 部署工具，要求显式提供 `--host` 和 `--config`。它复用目标机器已经准备好的 Python 3.12+、plocate 和 systemd，不自动安装软件包或推测权限设置；用户或 agent 也可以按本文手动部署。

将配置示例复制为自己的本地配置并填好后，从仓库根目录执行：

```sh
python deploy.py --host user@nas-host --config .local/server-config.json --check
python deploy.py --host user@nas-host --config .local/server-config.json --enable-service
```

`--check` 会临时上传源码并检查目标配置和依赖，结束后清理上传目录；不会切换服务或改写持久配置。正式部署会先在临时目录运行测试，再安装新版本并检查服务启动。密码只保存到远端密码文件和本机被忽略的连接文件，不在终端展示。

| 参数 | 用途 |
|---|---|
| `--host`、`--config` | 必填的 SSH 目标和服务端 JSON 配置 |
| `--update-config` | 显式更新已有配置；默认保留已有配置及旧版有效默认值 |
| `--url` | 客户端实际访问的 HTTP(S) 地址；监听所有地址时必须提供 |
| `--python` | 目标机器的 Python 命令或路径，默认 `python3` |
| `--enable-service` | 启用 systemd 用户服务自启动 |
| `--linger` | 请求启用运行用户的 linger，不会自动提升权限 |
| `--output` | 本机私有连接文件位置，默认 `.local/access.json` |

默认从有效配置的监听地址生成访问 URL；若监听回环地址，该 URL 也只用于本机或 SSH 转发。使用代理或其他访问入口时通过 `--url` 指定。

## 准备运行环境

服务端在 Ubuntu 24.04、Python 3.12 和 plocate 1.1.19 环境验证过。需要 Linux inotify；通常以能读取目标目录的普通用户运行即可。

优先复用可用的 plocate / updatedb，也可以将发行版软件包解包到用户目录。解包不会自动安装系统依赖，应检查二进制能否运行；发行版安装方式还可能自带系统级 updatedb 定时任务，应核对其用途和扫描范围，避免额外扫描，也不要未经核对停用现有任务。

把仓库源码放到目标机器。推荐将代码与状态放在系统 SSD，数据继续留在原目录。无需部署独立数据库服务。

## 显式配置

建议的用户目录约定：

| 内容 | 路径 |
|---|---|
| 配置与密码 | `~/.config/nas-find/` |
| 索引与临时查询 | `~/.local/state/nas-find/` |
| 版本化源码 | `~/.local/share/nas-find/releases/<版本>/` |
| 当前版本入口 | `~/.local/share/nas-find/current` |

首次部署时复制[配置示例](../examples/server-config.json)，然后填写自己的目录、共享和程序路径：

```sh
mkdir -p ~/.config/nas-find
cp -n examples/server-config.json ~/.config/nas-find/config.json
```

这里的 `cp -n` 会保留已经存在的配置。配置字段以示例文件为准，文档不重复维护第二份 JSON。

模板只允许 NAS 本机访问，便于先验证。需要局域网直接访问时，将 `bind` 改为 NAS 的实际局域网地址，并把 `allowed_networks` 改为实际可信网段；两个字段都要配置。服务目前按可信局域网 HTTP 设计，不能直接当成互联网公开服务。

`root` 是 Linux 上的真实索引根目录，`unc_prefix` 是 Windows 访问同一根目录的共享路径，二者必须对应。`require_mount=true` 当前检查的是 `root` 自身是否为挂载点；若索引普通子目录，需要评估挂载丢失时的保护，不能仅为绕过报错就关闭检查。

`exclude_paths` 默认是空列表；按目标机器填写需要排除的相对目录。首次索引会读取目录，选择合理的排除范围后再启动。

在 `password_file` 指定的位置生成随机密码，至少 12 个字符，并限制文件权限为仅运行用户可读写。保留现有密码，不将其写入代码、命令输出或公开文档。配置中带 `~` 的这些路径由程序按运行用户展开。

## 启动与自启动

先在源码根目录前台启动，确认配置可以加载、索引完成、没有监听错误：

```sh
python3 -m nasfind --config ~/.config/nas-find/config.json
```

长期运行可使用 [systemd 用户服务模板](../examples/nas-find.service)。若采用上面的版本目录，将 `current` 指向已验证的源码版本；检查模板里的 `WorkingDirectory` 和 Python 路径，再将服务文件放入 `~/.config/systemd/user/nas-find.service`。

核实 Python 路径与源码位置，再启用服务：

```sh
systemctl --user daemon-reload
systemctl --user enable --now nas-find
```

需要开机启动且退出 SSH 后继续运行时，核实该用户的 linger 配置；如需调整，按目标机器的权限策略使用 `loginctl enable-linger <运行用户>`。不要同时运行前台实例和同端口服务。

## 客户端和验收

通过网页登录验证服务；若只监听回环地址，可先使用 SSH 本地转发测试。Windows / macOS 客户端的构建安装见[开发文档](development.md)。首次启动时服务和共享地址为空，需填写自己的连接信息；已有用户保存的地址和映射盘不会被新默认值覆盖。

脚本生成的 `.local/access.json` 含 `client_config` 和密码，可在退出客户端后通过安装脚本的 `-ImportExistingConnection` 导入。同一共享会保留客户端已有的映射盘偏好。旧的、只含 URL 的连接文件可用于已配置客户端；新机器需补齐共享信息或手动配置。

macOS 用 Finder 连接该 SMB 共享，并在客户端填写实际的 `/Volumes/...` 挂载路径；SSH 登录不等于 SMB 登录。服务器和共享名需与客户端配置一致，索引服务继续运行在 NAS/Linux 上。

验收至少包括：已知文件与排除项、名称 / 路径匹配区别、超过一页的结果、跨页选择、复制 / 导出数量，以及通过配置的共享打开原文件。使用自有测试文件，避免为验收重启整个 NAS。

观察无文件名变化时索引更新次数不再增加。首次建立、服务重启恢复、通知溢出和默认每 7 天一次兜底仍会检查目录；这些不是常规空闲扫描。

## 更新、回滚和排障

更新前记录当前源码版本和配置位置。先在临时目录运行相关测试，再切换源码并重启用户服务；保留配置、密码、索引及上一个版本。需要回滚时恢复旧源码入口后重启，不删除数据目录或凭据。

使用 `deploy.py` 更新时，会先调用已安装版本的配置加载器，展开旧版隐含默认值；新配置校验通过后，才将有效配置写回文件。旧配置备份保存在配置目录的 `backups/`。即使新代码不再提供某个环境默认值，原来的排除范围与允许网段仍会保留。

只有 `--update-config` 才合并新输入。索引状态目录或密码文件位置变化需要单独迁移，脚本会拒绝直接切换。新版本激活或 HTTP 启动检查失败时，恢复旧版本入口、配置和服务单元；SSH 中断或命令超时后，应先检查远端状态再重试。

```sh
systemctl --user status nas-find
journalctl --user -u nas-find -n 50 --no-pager
systemctl --user restart nas-find
```

连接失败先检查地址、端口、来源网段和服务状态；索引不可用检查挂载与目录权限；监听异常检查 inotify 额度和日志；查询过期则重新搜索，不把旧选择套用到新结果。

运行状态和查询缓存位于 `state_dir`，不是数据目录。不要为清理缓存误删源文件；文档修改本身不要求重启服务或重建索引。

# 部署与维护

本文介绍 Docker 和原生 Linux 部署的安装、配置、更新与排障。示例中的路径、主机名和网段需替换为自己的环境信息。

## 先确认环境

| 信息 | 需要明确的内容 |
|---|---|
| NAS | 主机、Linux 发行版、CPU 架构、SSH 用户与授权范围 |
| 数据目录 | 实际本地路径、挂载点、文件读取权限、排除项 |
| 运行目录 | 代码、索引与查询缓存的存放位置，建议使用系统 SSD |
| 网络 | 监听地址、端口、允许访问的网段 |
| 文件访问 | UNC 共享根路径，以及 Windows 上可选的映射盘 / macOS 上实际的 SMB 挂载目录 |
| 已有部署 | 配置、密码、服务和索引是否已存在；更新时应保留哪些内容 |

## 部署方式

支持 Docker 和原生 Python + systemd 两种部署方式。Docker 部署中，Ubuntu / Debian 等 Linux 使用 Compose，Unraid 使用安装模板，两者共用同一镜像。

## Docker 部署（Ubuntu / 通用 Linux）

镜像包含 Python 3.12、plocate 和网页，无需在宿主安装这些依赖。需要安装 Docker Engine 与 Compose v2。下面按源码构建部署；镜像构建与发布流程见[开发文档](development.md)。

一个实例索引一个 SMB 共享根目录。准备宿主上的共享数据目录、对应的 SMB 共享根路径，以及索引范围外的独立配置目录。

在 Linux 上获取源码后执行：

```sh
git clone https://github.com/chess99/nas-find.git
cd nas-find
mkdir -p .local
cp -n examples/docker.env .local/docker.env
id -u
id -g
```

编辑 `.local/docker.env`，将示例值全部换成自己的目录、IP、网段、共享名和 UID/GID。`NAS_FIND_BIND_IP` 为 NAS 实际局域网 IP；`NAS_FIND_ALLOWED_NETWORKS` 为允许访问的客户端网段，多个网段用逗号分隔。UNC 值保留示例的单引号，不要额外增加反斜杠。运行用户/组需要有数据目录的遍历和读取权限。

确认数据目录已经存在且数据盘已挂载；另外手动创建环境文件中填写的专用配置目录，例如 `mkdir -p /实际配置目录`。Compose 不会自动创建缺失的目录，以免路径填错后扫描空目录。然后在仓库根目录启动：

```sh
docker compose --env-file .local/docker.env up -d --build
docker compose --env-file .local/docker.env ps
docker compose --env-file .local/docker.env logs --tail=50
```

首次启动会生成配置目录中的 `config.json`、`password` 和 `state/`。用自己的运行账号或管理员读取宿主配置目录中的 `password`，然后访问 `http://<NAS局域网IP>:<端口>` 登录。密码不会写入容器日志，也不需要放进环境变量。首次索引可能耗时较长，Docker 显示 `healthy` 仅表示 HTTP 服务可访问，索引是否完成以网页登录后的状态为准。

| 映射 / 参数 | 行为 |
|---|---|
| 数据目录 → `/data:ro` | 只读访问源文件，支持搜索、网页预览和下载 |
| 专用目录 → `/config:rw` | 保存配置、密码、索引和查询缓存，建议放系统 SSD |
| `PUID` / `PGID` | 服务进程运行的用户和组，需要有数据目录的遍历和读取权限 |
| `NAS_FIND_UNC_PREFIX` | 与 `/data` 对应的 SMB 根路径；每次启动以显式环境变量覆盖保存值 |
| `NAS_FIND_ALLOWED_NETWORKS` | 每次启动以显式环境变量覆盖；自动加入容器内部健康检查使用的回环网段 |

停止容器后，可编辑 `config.json` 的 `exclude_paths`、`exclude_names`、`update_interval`、`debounce_seconds`、`reconcile_interval` 等高级项，再启动容器。容器内的目录、监听端口和程序路径由镜像固定，宿主路径与访问端口通过 Compose 映射设置。更改环境文件后使用 `up -d` 重建容器，`restart` 不会加载新环境变量。

自定义登录密码时，在首次启动前将至少 12 个字符的密码写入配置目录的 `password`，文件权限设为仅运行 UID 可读写。

更改 PUID/PGID 前，先停止容器，再将专用配置目录及其文件的属主调整为新的 UID/GID，否则服务可能无法读取原有配置和索引。

容器启动时设置专用配置目录属主，然后以 PUID/PGID 指定的用户运行服务。也可事先准备目录权限，通过 Docker `--user UID:GID` 直接指定运行用户，此时忽略 PUID/PGID。

### Unraid 安装

使用同一镜像，通过网页配置目录和变量。安装模板在 [examples/unraid/nas-find.xml](../examples/unraid/nas-find.xml)。

1. 将模板复制到 Unraid 的 `/boot/config/plugins/dockerMan/templates-user/my-nas-find.xml`。在 Docker 页选择 Add Container，再从 Template 下拉框选择 `nas-find`。
2. 索引目录填写实际 `/mnt/user/<共享名>`，保持只读；配置目录使用索引范围外的独立 appdata 子目录，建议将 appdata 放到 SSD 池。
3. 填写与索引目录对应的 SMB 根路径、实际可信网段和端口。模板默认 UID/GID 为常见的 `99:100`，仍需核实目录权限。启动后从 appdata 中的 `password` 读取密码登录。

也可以在 Unraid 本机获取源码并执行 `docker build -t nas-find:local .`，然后将模板 Repository 改为 `nas-find:local`，使用本机构建的镜像。

在 Unraid 上，如果通过 SMB 修改文件或 Mover 搬移后搜索结果没有更新，可在网页手动更新；也可停止容器后将 `reconcile_interval` 改为 `3600` 等合适间隔进行定期校验。设置为 `3600` 会每小时检查目录，可能唤醒机械盘；没有触发文件事件的变化会在校验后显示。

### Docker 更新与排障

- 数据应直接来自 NAS 本机存储。容器映射的远程 SMB/NFS 客户端挂载不一定收到其他机器的变化通知；这类目录需手动更新或缩短兜底校验间隔。
- 开机时先挂载数据盘或启动 Unraid 阵列，再启动容器。磁盘掉线时停止容器，恢复挂载后重建容器，避免扫描到挂载点下的空目录。
- 容器会索引映射范围内可见的嵌套挂载，不需要的子目录通过 `exclude_paths` 排除。原生部署需要搜索 bind mount 时，将 `prune_bind_mounts` 设为 `false`。
- 数据目录权限不足时，检查 PUID/PGID。若读取权限来自另一个组，改用该组的 PGID，或为运行用户授予读取权限。
- HTTP 端口只用于可信局域网。Docker bridge 通常保留局域网客户端 IP，但代理可能改变来源；遇到 403 先检查实际来源和网段配置。Docker 发布端口与宿主防火墙的交互不同于原生服务，不能只依赖 UFW 的普通入站规则；限定绑定地址并避免路由器公网转发。
- 大目录监听报额度不足时，在 Linux 宿主检查并调整 `fs.inotify.max_user_watches` / `max_user_instances`；容器共用宿主内核额度。

源代码构建的更新：记录当前 Git 提交和镜像 ID，备份专用配置目录，获取新版本后再次执行 `up -d --build`。若使用已发布镜像，把环境文件中的 `NAS_FIND_IMAGE` 设为固定版本，再执行：

```sh
docker compose --env-file .local/docker.env pull
docker compose --env-file .local/docker.env up -d --no-build
```

首次使用发布镜像同样采用这两条命令。Unraid 在 Docker 页更新容器。更新时保留 `/config` 的宿主目录和路径映射，以继续使用原有配置、密码及索引；回滚时恢复之前记录的镜像版本和必要的配置备份。

## 原生部署

准备 Python 3.12+、plocate 和 systemd 后，将源码放到目标机器，按[配置示例](../examples/server-config.json)填写配置，使用 [systemd 用户服务模板](../examples/nas-find.service)启动服务。

通过 SSH 部署时，可使用 `deploy.py`，指定 `--host` 和 `--config`。目标机器需事先安装上述依赖。

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

`cp -n` 会保留已经存在的配置，完整配置字段见示例文件。

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

通过网页登录验证服务；若只监听回环地址，可先使用 SSH 本地转发测试。Windows / macOS 客户端的构建安装见[开发文档](development.md)。首次启动客户端时，填写服务地址和共享路径。

脚本生成的 `.local/access.json` 含 `client_config` 和密码，可在退出客户端后通过安装脚本的 `-ImportExistingConnection` 导入。同一共享会保留客户端已有的映射盘偏好。旧的、只含 URL 的连接文件可用于已配置客户端；新机器需补齐共享信息或手动配置。

macOS 用 Finder 连接该 SMB 共享，并在客户端填写实际的 `/Volumes/...` 挂载路径。服务器和共享名需与客户端配置一致。

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

运行状态和查询缓存保存在 `state_dir`，与源文件所在的数据目录分开。

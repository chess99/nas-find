# NAS Find

面向个人 NAS 的轻量文件名搜索：plocate + Linux inotify + Python 标准库网页服务。支持中文子串、多个关键词、目录与扩展名筛选、文件预览、下载、复制 Windows UNC 路径。

## 运行方式

工程主目录：`D:\code\nas-find`。目标机器：Ubuntu 24.04、`192.168.0.104`，共享根目录 `/mnt/Disk1`。

在 Windows 运行：

```powershell
python deploy.py --host ubuntu
```

部署使用已有 SSH 密钥和普通 `zcs` 用户。将 Ubuntu 官方的 plocate 1.1.19-2ubuntu2 软件包解包到私有目录，不修改系统已安装软件；先在 SSD 临时目录运行集成测试，通过后切换版本并启动用户级 systemd 服务。启用该用户的 linger 以便开机启动、退出 SSH 后继续运行。

访问地址：`http://192.168.0.104:8765`。登录密码保存在本机 `.local/访问说明.txt`，不会进入 Git。服务监听指定局域网地址，仅接受配置中允许的来源网段。

## 索引维护

- 数据库、服务代码、日志和运行状态在系统 SSD；文件仍在原 NAS 数据盘。
- 首次运行会注册目录监听并建立索引，会读取数据盘。
- 创建、删除、重命名文件或目录后标记待更新，合并事件；默认最多每小时进行一次常规更新，等待至少 30 秒没有新事件。
- **没有事件时不运行常规更新器**。仅修改正文不会触发文件名索引更新。
- plocate 更新时仍检查目录，复用未变化目录的文件名记录；不是逐文件可修改的数据库。
- 重启后校验停机期间的变化；通知溢出后重建监听并校验；每 7 天进行一次兜底校验。
- 新快照在 SSD 上完成、校验后原子替换，搜索继续使用旧快照。挂载失败或更新器失败时保留旧快照。
- 搜索只读索引，不逐条检查实际文件是否存在，也不获取大小、日期或缩略图。预览或下载时才读取实际文件。

默认排除目录名：`.git`、`node_modules`、`.pnpm-store`、`.venv`、`__pycache__`。

默认排除相对目录：`docker-volume/photoprism/cache`、`docker-volume/photoprism_mariadb`。保留其他应用数据、备份、PhotoPrism sidecar 和用户内容。目录名排除也会隐藏同名索引结果。

## 文件访问

浏览器支持文本（前 64 KiB）、常见图片、PDF、兼容的音视频预览和文件下载。音视频支持 HTTP Range，可跳播。Office 文档、压缩包及浏览器不支持的媒体可下载或复制 UNC 路径到 Windows 打开。没有安装本地协议助手，因此网页不直接唤起资源管理器。

所有文件操作为读取，不提供删除、移动、上传功能。路径限制和逐级 `O_NOFOLLOW` 文件描述符访问拒绝目录跳转及符号链接；特殊设备文件不提供下载。非文本 HTML/SVG 不以活动页面内联显示。搜索结果中的符号链接可能可见，但访问会被拒绝。

搜索以 plocate 规则工作：普通词为子串，多词 AND；引号保留含空格短语；通配符是完整路径匹配，例如 `*.pdf`。不提供正则模式。结果默认前 100 条，候选最多 10,001 条，查询超时 3 秒；过宽的搜索应增加关键词。目录筛选使用相对路径，例如 `code`。目录条目来自最近一次索引发布时的监听目录快照。

登录会话持续至服务重启或 30 天；密码是随机生成并保存在权限为 0600 的文件中。当前部署面向可信局域网 HTTP 使用，不作为互联网公开服务。

## 维护

远程路径：

| 内容 | 路径 |
|---|---|
| 当前版本 | `~/.local/share/nas-find/current` |
| 历史版本 | `~/.local/share/nas-find/releases/` |
| plocate 程序 | `~/.local/share/nas-find/vendor/` |
| 配置、密码 | `~/.config/nas-find/` |
| 索引、状态 | `~/.local/state/nas-find/` |
| systemd 单元 | `~/.config/systemd/user/nas-find.service` |

```sh
systemctl --user status nas-find
journalctl --user -u nas-find -n 50 --no-pager
systemctl --user restart nas-find
systemctl --user disable --now nas-find
```

更改 `~/.config/nas-find/config.json` 后重启生效。可覆盖 `exclude_names`、`exclude_paths`、`update_interval`、`debounce_seconds`、`reconcile_interval`、`port` 等字段；默认值在 `nasfind/config.py`。

重新执行部署会保留配置、密码、索引及旧版本。版本信息保存在 `~/.local/share/nas-find/deployment.json`，`previous` 为上次版本路径；需要回滚时将 `current` 符号链接切回该路径，然后重启服务。

## 验证

本次真实部署的容量、延迟、运行状态和验证边界见 [部署与验证记录](docs/验证记录.md)。

Linux 上指定解包后的真实 plocate 程序运行：

```sh
PLOCATE_BIN=/path/to/usr/bin/plocate \
UPDATEDB_BIN=/path/to/usr/sbin/updatedb.plocate \
python3 -m unittest discover -s tests -v
```

测试涵盖：中文中间片段、空目录、排除规则、无变化和正文修改时不更新、创建/移动/删除目录、离线期间变更恢复、通知溢出恢复、更新失败保留快照、登录、跨来源请求、路径越界/符号链接、空文件、HEAD 和 Range 下载。测试只在系统临时目录创建自有文件。

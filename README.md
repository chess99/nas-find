# NAS Find

**在 Windows、Mac 和 Android 手机上，快速找到 NAS 里的文件。**

面向 Linux / NAS 的文件名搜索工具，提供网页界面、Windows / macOS 桌面客户端和 Android 客户端。

索引在 NAS 本机统一维护，多个设备共用，客户端无需各自扫描共享目录建立索引。搜索结果按需传输，桌面客户端通过 SMB 共享（Windows UNC / 映射盘，macOS Finder 挂载目录），用系统默认应用打开原文件。

Android 客户端支持搜索、筛选、预览、保存文件和导出路径清单。用其他应用打开时先下载临时副本，修改不会写回 NAS。目前提供源码构建和 Actions 调试包，安装见[开发文档](docs/development.md#android-构建与安装)，操作见[使用说明](docs/usage.md#android-客户端)。

## 定位与取舍

NAS Find 面向已有 SMB 共享、主要按文件名查找的用户：在电脑上搜索 NAS 文件，找到后直接用系统应用打开。

| 工具 | 优点 | 取舍 |
|---|---|---|
| [Everything＋网络文件夹](https://www.voidtools.com/support/everything/folder_indexing/) | 无需部署 NAS 服务，支持大小和日期筛选 | 仅支持 Windows；每台电脑需分别维护索引 |
| **NAS Find** | Windows、macOS 和网页共用 NAS 上的一份索引 | 需部署轻量服务；暂不支持大小和日期筛选 |
| [kodbox](https://github.com/kalcaddle/kodbox) | 编辑、分享、协作和文件管理功能完整 | 需维护 Web 服务和数据库 |
| [sist2](https://github.com/simon987/sist2) | 支持正文、元数据搜索、缩略图和可选 OCR | 内容提取和缩略图生成会增加索引处理量 |

NAS Find 只索引名称与路径，不在后台解析正文、EXIF 或音视频元数据，也不预生成缩略图。文件名变化合并后更新索引，预览和打开时才按需读取原文件。

## 功能

- 中文片段和多关键词搜索，默认匹配名称，可切换为匹配路径。
- 按视频、音频、图片、文档等类型筛选，也可指定扩展名和目录范围。
- 全结果分页浏览、虚拟列表、跨页多选和全选。
- 批量复制路径，导出 TXT 或保留特殊文件名的 CSV 清单。
- 网页预览与下载，Windows / macOS 默认应用打开和文件定位。
- 使用 inotify 合并文件名变化；没有变化时不执行常规索引更新。

服务端使用 Python 标准库和 plocate，桌面端使用 Tauri。无需独立数据库服务。

## 开始使用

1. 按[部署文档](docs/deployment.md)在 NAS 上通过 Docker 或原生方式启动服务。
2. 使用浏览器访问服务，或从 [Releases](https://github.com/chess99/nas-find/releases) 下载 Windows 64 位 / Mac M 系列安装包。本地构建见[开发文档](docs/development.md)。
3. 在客户端填写自己的服务地址和共享路径，参阅[使用说明](docs/usage.md)。

也可以把仓库交给 AI agent，要求它先读取 [AGENTS.md](AGENTS.md)，再按同一份部署文档执行。

## 适用范围

NAS Find 搜索文件名与路径，不做文件正文全文检索。查询只读取索引；预览、打开和下载时才访问实际文件。

首次建立索引、服务重启后的恢复校验和默认每 7 天一次的兜底校验会检查数据目录。单次复制路径最多 16 MiB，更大的清单可导出为文件。

当前服务按可信局域网设计；桌面端的文件访问权限由系统和 SMB 共享控制。

## 文档与贡献

[文档目录](docs/README.md) · [使用说明](docs/usage.md) · [部署](docs/deployment.md) · [开发与测试](docs/development.md)

欢迎提交问题和改进建议。修改行为时请同步更新对应文档；当前操作说明与历史记录分开维护，人与 AI 共用文档。

## 许可证

本项目原创代码采用 [GNU AGPL 第 3 版](LICENSE)，SPDX 标识为 `AGPL-3.0-only`。第三方依赖及 `vendor/` 中的代码保留各自的许可证和版权声明。

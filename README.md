# NAS Find

面向 Linux / NAS 的文件名搜索工具，提供网页界面和 Windows / macOS 桌面客户端。

索引在 NAS 上维护，搜索结果按需传输。桌面客户端通过 SMB 共享（Windows UNC / 映射盘，macOS Finder 挂载目录），用系统默认应用打开原文件。

## 定位与取舍

NAS Find 面向已有文件共享、主要按文件名查找的用户：希望在 NAS 上获得接近 Everything 的搜索和打开体验，同时保持部署简单、减少数据盘的多余访问。

| 工具 | 功能侧重 |
|---|---|
| [Everything](https://www.voidtools.com/support/everything/folder_indexing/) | Windows 本地快速搜索；支持网络文件夹索引，但机制不同于本地 NTFS 索引 |
| [kodbox](https://github.com/kalcaddle/kodbox) | 网盘与文件管理，包含在线编辑、分享和协作等功能 |
| [sist2](https://github.com/sist2app/sist2) | 内容检索，提供文本与元数据提取、缩略图及可选 OCR 等能力 |
| **NAS Find** | NAS 端文件名索引、快速浏览与批量路径操作，通过原有共享打开文件 |

我们优先做好“找到文件，再打开它”：不在后台解析正文、EXIF 或音视频元数据，也不预生成缩略图，预览时才按需读取文件。可以接受新文件稍后出现在索引中，优先减少无变化时的目录遍历。

## 功能

- 中文片段和多关键词搜索，默认匹配名称，可切换为匹配路径。
- 按视频、音频、图片、文档等类型筛选，也可指定扩展名和目录范围。
- 全结果分页浏览、虚拟列表、跨页多选和全选。
- 批量复制路径，导出 TXT 或保留特殊文件名的 CSV 清单。
- 网页预览与下载，Windows / macOS 默认应用打开和文件定位。
- 使用 inotify 合并文件名变化；没有变化时不执行常规索引更新。

服务端使用 Python 标准库和 plocate，桌面端使用 Tauri。无需独立数据库服务。

## 开始使用

1. 在 Linux 上准备 plocate 和索引目录，按[部署文档](docs/deployment.md)配置并启动服务。
2. 使用浏览器访问服务，或按[开发文档](docs/development.md)构建 Windows 或 macOS 客户端。
3. 在客户端填写自己的服务地址和共享路径，参阅[使用说明](docs/usage.md)。

也可以把仓库交给 AI agent，要求它先读取 [AGENTS.md](AGENTS.md)，再按同一份部署文档执行。

部署时需要指定索引目录、共享地址和访问网段；配置示例与服务模板见[部署文档](docs/deployment.md)。

## 适用范围

NAS Find 搜索文件名与路径，不做文件正文全文检索。查询只读取索引；预览、打开和下载时才访问实际文件。

首次建立索引、服务重启后的恢复校验和默认每 7 天一次的兜底校验会检查数据目录。单次复制路径最多 16 MiB，更大的清单可导出为文件。

当前服务按可信局域网设计；桌面端的文件访问权限由系统和 SMB 共享控制。

## 文档与贡献

[文档目录](docs/README.md) · [使用说明](docs/usage.md) · [部署](docs/deployment.md) · [开发与测试](docs/development.md)

欢迎提交问题和改进建议。修改行为时请同步更新对应文档；当前操作说明与历史记录分开维护，人与 AI 共用文档。

## 许可证

本项目原创代码采用 [GNU AGPL 第 3 版](LICENSE)，SPDX 标识为 `AGPL-3.0-only`。第三方依赖及 `vendor/` 中的代码保留各自的许可证和版权声明。

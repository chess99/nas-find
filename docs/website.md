# 官网维护

官网地址为 <https://nas-find.cearl.cc/>，英文入口为 <https://nas-find.cearl.cc/en/>。页面使用 Node.js 生成静态 HTML，由 GitHub Pages 托管。生产页面没有框架运行时，也不读取或连接 NAS 服务。

## 内容与文件

| 文件 | 用途 |
|---|---|
| `website/src/content.mjs` | 中文、英文文案；修改功能说明时同时更新两种语言 |
| `website/site.config.mjs` | 正式域名、已公开的稳定版本、内容核对日期、统计 ID |
| `website/build.mjs` | 页面模板、结构化数据、语言替代链接及搜索抓取文件 |
| `website/public/assets/` | 样式、交互、统计、图标、真实界面与分享预览图片 |
| `website/tools/screenshots.mjs` | 使用真实桌面 HTML / 共用 Explorer 与合成文件重新生成截图 |
| `.github/workflows/website.yml` | 构建、检查和部署 |

中文使用根路径，英文使用 `/en/`；首页、开始使用、隐私说明均有独立页面。切换语言保留对应页面，不按浏览器语言自动跳转。英文开始使用页提供英文介绍；链接到中文仓库详述时标明语言。客户端截图保留真实中文 UI，英文图注明确说明。

官网开始使用页提供部署与连接的入口说明，具体参数继续维护在[部署文档](deployment.md)和[使用说明](usage.md)。官网不复制整套安装命令，避免产生相互冲突的配置来源。

下载链接固定到 `site.config.mjs` 中**已公开的稳定版**，不读取可能仍未发布的根目录产品版本，也不依赖访客实时访问 GitHub API。产品正式发布后，确认三个安装包已公开，再更新官网的 `release`、对应能力说明和 `reviewed`，提交官网变更即可发布。保持 `reviewed` 为实际核对日期，不在每次构建时刷新。

## 本地预览与验证

使用 Node.js 22 或更新的 LTS 版本：

```sh
cd website
npm ci
npm run dev
```

打开终端给出的 `http://127.0.0.1:4173`。预览服务器仅监听本机，源文件修改后重新执行构建，再刷新页面；构建结果在被忽略的 `website/dist/`。不同任务需要同时预览时可设置 `PORT`。

```sh
npm test
npx playwright install chromium
npm run test:browser
```

浏览器检查覆盖 1440 / 768 / 390 / 320 像素宽度、两种语言、资源加载、无障碍、键盘 FAQ、关闭 JavaScript、404，以及生产域名下的模拟统计与退出统计。测试拦截第三方统计请求，不向真实统计账户发送测试访问。截图和报告保存在被忽略的 `.local/website/`。

已有 Chrome 时可设置 `BROWSER_CHANNEL=chrome` 复用浏览器引擎。例如 PowerShell：

```powershell
$env:BROWSER_CHANNEL = 'chrome'
npm run test:browser
```

更新客户端界面后，在装有中文字体的环境中重新生成官网素材：

```sh
npm run screenshots
npm test
npm run test:browser
```

截图脚本只读取仓库中的界面源文件，使用固定合成数据，不读取 `.local/` 的 NAS 配置或真实目录。截图中的查询耗时隐藏，不能作为性能测量。网站使用无损 WebP；两种语言的社交分享图使用 1200 × 630 PNG。检查生成图后再提交。

## 自动发布

GitHub 仓库 Settings → Pages 的 Source 使用 **GitHub Actions**，Custom domain 为 `nas-find.cearl.cc`。DNS CNAME 指向 `chess99.github.io`；HTTPS 证书签发后启用 **Enforce HTTPS**。构建产物同时包含 `CNAME` 和 `.nojekyll`。

`Website` 工作流的触发规则：

| 事件 | 行为 |
|---|---|
| main 上修改 `website/**` 或 `.github/workflows/website.yml` | 检查通过后自动发布 |
| PR 修改上述路径 | 仅构建与检查，不部署 |
| Actions 手动运行 Website，选择 main | 检查通过后发布 |
| 手动选择其他分支 | 仅构建与检查，不部署 |
| 仅修改客户端、服务端、仓库文档，或推送产品版本 tag | 不触发官网工作流 |

官网改动通常可以独立发布，因此使用路径过滤即可，无需单独维护官网 tag。产品 `v*` 标签仍由已有 `Release` 工作流处理。官网流程只有部署任务获得 `pages: write` 和 `id-token: write` 权限，使用 `github-pages` 环境。

回滚官网时，撤销有问题的官网提交并推送 main；也可恢复上一版官网文件后提交。不要只重跑旧工作流来永久回滚，否则后续 main 构建仍会重新部署新文件。

## SEO 与信息可信度

- 所有主要内容直接输出在 HTML 中，禁用 JavaScript 仍可阅读、切换语言和下载。
- 每页有独立 title、description、self-canonical，以及相互对应的 `zh-CN`、`en`、`x-default` 链接。
- 首页输出 `WebSite`、`WebPage`、`Person`、`SoftwareApplication` JSON-LD，内页包含面包屑。维护者为公开 GitHub 身份，不虚构组织、评分或评价。
- 自动生成 `robots.txt`、`sitemap.xml` 和 `llms.txt`，404 为 `noindex`。不将 `llms.txt` 或结构化数据视为收录、排名或富结果保证。
- Open Graph / Twitter 分享信息使用独立中文、英文预览图；静态图片有明确宽高，首屏截图优先加载，页面使用系统字体。
- 使用能力、局限、许可证、源码、版本说明和可复现的问题反馈入口建立可信度。没有经过实际测量的数据不写成性能或用户数量承诺。

上线后向自己管理的 Google Search Console、Bing Webmaster Tools 和百度搜索资源平台验证域名并提交 `https://nas-find.cearl.cc/sitemap.xml`。生成 sitemap 不代表已提交搜索平台；浏览器检查也不代表已有真实用户 Core Web Vitals 或搜索排名数据。需要验证文件时放入 `website/public/`，不要提交平台密钥。

设计和内容参考：[Raycast](https://www.raycast.com/) 的直接产品定位、[Tailscale](https://tailscale.com/) 的自托管设备关系表达，以及 [Everything Folder Indexing](https://www.voidtools.com/support/everything/folder_indexing/) 的网络文件夹使用边界。未复用其图片、客户标志或评价。SEO 实施参考本地 `webmaster-skills` 的公开证据、结构化数据与统计建议；当前没有关键词排名或流量导出数据。

## 统计事件

Google Analytics：`G-PMEQP0J5PZ`；百度统计：`cf4099b2d694865a12c5932449fda206`。这些是公开的客户端站点 ID。统计在正式域名空闲加载，用户提前点击时立即初始化，同一页面只初始化一次。本机、其他预览域名、主动退出统计或浏览器启用 DNT / GPC 时不加载。

| 事件 | 触发与分析用途 |
|---|---|
| `page_view` / 百度页面浏览 | 页面访问，GA 初始化发送；百度关闭自动 PV 后显式记录路径 |
| `cta_click` | 从首页或指引进入下载区 |
| `download_click` | Windows、macOS、Android 安装包点击 |
| `docs_click` | 部署、连接、操作文档和 Web 使用入口 |
| `release_click` | 版本说明、校验文件与全部下载 |
| `source_click` | 源码、许可证、维护者与问题反馈 |
| `language_switch` | 切换语言 |
| `faq_open` | 展开具体问题 |
| `screenshot_open` | 查看完整产品截图 |

GA 自定义参数为 `language`、`page`、`target`、`placement`；均来自固定页面标记。百度事件类别为 `website`，标签按这四个字段组合。建议在 GA 管理端注册这些事件级自定义维度，并按平台查看 `download_click`。如开启 GA 增强型衡量的出站点击或下载事件，它们与本站自定义事件分开分析，不合并重复计算。安装包点击只说明下载意图，不证明下载、安装或连接成功。

本站自定义事件不包含任意链接、搜索框文本、NAS 地址、文件名或路径。GA 的初始 page_location 去掉查询串和片段，referrer 仅保留来源域名；百度显式页面路径也不带查询串。第三方 SDK 自身仍按各自政策处理网络与页面信息，详见官网隐私页。关闭统计的本地偏好不会自动清除第三方已收到的数据和既有 Cookie。

生产模拟测试验证脚本 ID、单次初始化、事件参数与隐私开关，不能替代统计后台收数验收。上线后在 GA Realtime / DebugView 和百度实时访客中检查实际收数；这需要相应账号权限。

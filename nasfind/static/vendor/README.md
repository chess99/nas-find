# 网页播放依赖

`mpegts-1.8.2.min.js` 来自 [mpegts.js 1.8.2](https://www.npmjs.com/package/mpegts.js/v/1.8.2) 发布包的 `dist/mpegts.js`，内容未修改，仅调整文件名。仅在预览 FLV、TS、MTS、M2TS 且需要封装适配时加载，由 NAS Find 本机提供，无外部 CDN 请求。

- 项目：[xqq/mpegts.js](https://github.com/xqq/mpegts.js)，Apache-2.0，完整许可证见 `mpegts-LICENSE`。
- 打包保留的声明：`mpegts.js.LICENSE.txt`。
- 包内依赖：es6-promise 4.2.8 与 events 3.3.0，MIT 许可证分别见 `es6-promise-LICENSE`、`events-LICENSE`。
- 发布包完整性：`sha512-cZYMa5muASH55wrS6JK5IGkUqnCA96c0f+WdnW91jIK9s/N6NzjmTQDJNYg9A5u/G2Q5nj2b3w1Zwzze/9guxA==`。
- 本地脚本 SHA-256：`bda31748736a69cb610c2edf4623e633f1f4f47b5bda83668c8d287e51b0c3a8`。

此脚本将支持的音视频流重新封装后交给浏览器解码。更新版本时同步调整静态资源允许列表、加载地址、许可证和校验值，并运行网页播放测试。构建不依赖在线下载此脚本。

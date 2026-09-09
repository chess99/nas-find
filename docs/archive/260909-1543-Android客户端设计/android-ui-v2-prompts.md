# Android 第二版概念图生图记录

本文件为本次设计迭代的历史生图记录。使用内置 imagegen 工具生成与编辑，没有使用 CLI/API 备用路径。最终交付为同目录的 `android-search-v2.png` 与 `android-actions-v2.png`，当时的交互提案见[设计方案](android-design.md)。所有内容均为示例。

## 搜索与筛选：初始生成

```text
Use case: ui-mockup
Create a second, substantially more refined UI design proposal for NAS Find Android, a focused personal NAS filename search app. This is a NEW design, not a cosmetic patch. Deliver a premium high fidelity product design sheet with THREE front-facing flat Android screens side by side, labeled outside "01 开始查找", "02 搜索结果", "03 筛选". Landscape image, large readable screens, no perspective. A small restrained outside title "NAS Find / Android" and subtitle "搜索与筛选 · 第二版概念". White/light warm neutral background. All data fictional.

VISUAL SYSTEM: understated professional Android productivity UI, inspired by excellent native file utilities. Screen canvas #FAFBFA, ink #25332E, muted text #66736C, evergreen accent #206853, selected pale green #E7F0EB, hairline borders #E3E8E4. Flat surfaces, 20dp horizontal spacing, coherent 8dp grid, 16sp Chinese primary text and 13sp secondary, 20sp top title. Search container height 52dp and radius 16dp. Sheets radius 24dp. Uniform custom outlined file icons inside small muted pastel rounded squares, no Adobe/Excel logos or gradients. Generous 48dp touch targets even on small icons. Typography, alignment and spacing must feel carefully art-directed. Slim device outlines, tiny shadows, Android status/gesture bars. No logos inside app, no app name inside app, no bottom navigation, no floating action button, no footer status, no decorative dashboards. Avoid huge headings, giant empty hero areas, marketing copy, ornamental illustrations.

COMMON HOME/RESULT HEADER: One compact single-line top app bar: left "家庭 NAS", immediately beside it a small green dot and smaller text "可搜索"; right one outline settings gear. This is the only place service/index readiness appears. Next a dominant rounded search field with magnifier, plus adjacent compact rounded-square sliders filter button (48dp). No second settings entry.

SCREEN 1 HOME: Search placeholder "搜索文件名". Do NOT show category chips before searching. Below with comfortable 28dp separation: small section title "最近搜索" and discreet "清空" right. Three clean history rows with outline clock, titles "旅行", "年度报告", "设计稿", trailing northeast arrow meaning reuse query (not crosses). Under the history group, one subtle full-width row with folder-search outline icon, label "浏览全部文件", right chevron. Modest empty space below is intentional; no extra cards, stats or duplicated instructions. Everything important begins near top. Only Android system gesture bar at bottom.

SCREEN 2 RESULTS: Same exact common header. Search field "旅行" and clear x, same sliders button. Below a single horizontally scrollable row of compact category chips "全部" selected dark green, "文档", "图片", "视频", with the next "音频" partly visible to imply scrolling. Count line "128 个结果" at left and "选择" at right. 7 generously tappable consistent file rows, each 70dp high: small custom PDF icon and "旅行计划.pdf", path "文档 / 旅行"; XLS icon and "旅行预算.xlsx", same path; TXT and "旅行日记.txt", same path; image icon and "旅行照片.jpg", path "照片 / 示例"; video icon and "旅行片段.mp4", path "视频 / 示例"; PDF and "旅行路线.pdf", path "文档 / 旅行"; folder icon and "旅行素材", path "归档". File name is boldish dark ink with only 旅行 accented green; path one muted line; kebab menu right for files and chevron for folder. Thin separators indented after icons. No sizes, dates, thumbnails, loading spinners or sorting. Rows use available height all the way to bottom system inset. No app bottom bar.

SCREEN 3 FILTER SHEET: Same results screen behind a softly dimmed scrim. Tall bottom sheet taking approximately 80 percent of screen. Drag handle, header "筛选" with close x. Labels and controls align carefully. Section "文件类型" with 3-column wrapping chips: 全部 (selected), 文档, 图片 / 视频, 音频, 文件夹 / 压缩包, 程序. Section "搜索范围" with outlined input showing "全部目录" and a secondary subdued example "填写 NAS 内的相对目录". Section "扩展名" with outlined input placeholder "例如 pdf". One row "同时匹配路径" with switch OFF and small helper "也搜索文件所在的目录名称". Bottom sticky actions above gesture inset: small outlined "重置" and wide primary green "应用筛选". Don't preselect any other category. Internal scroll possible; keep controls uncluttered and readable, no keyboard. No duplicate category tabs inside sheet.
Constraints: accurate Chinese typesetting, clear hierarchy, visual consistency, realistic implementable app screens, not a slide packed with callouts. Restrained high craft. No real addresses or private data.
```

## 搜索与筛选：精确修订

```text
Use case: precise-object-edit. Edit this NAS Find Android concept board with ONLY these corrections, preserving every other pixel/layout/color/style as much as possible. On the RIGHT screen's filter bottom sheet, replace the file-type chip grid with exactly EIGHT separate chips laid out 3 columns across 3 rows: first row "全部" (green selected), "文档", "图片"; second row "视频", "音频", "文件夹"; third row "压缩包", "程序", empty space. "文件夹" and "压缩包" must each have their own distinct chip, never merge them. Make room by gently tightening vertical whitespace below this grid; preserve the legibility and bottom sticky action row. Remove the small folder icon from the right end of the "全部目录" input, leaving a plain text entry (no directory picker). On the middle and right search-result screens change the count line to "128 个结果 · 含子目录". All other screens, content, typography, labels and device frames remain unchanged. This is a precise UI correction, not a redesign.
```

## 文件操作：生成（以搜索图作风格参考）

```text
Use case: ui-mockup
Create a companion high fidelity Android UI concept sheet for NAS Find. The attached board is STYLE REFERENCE ONLY, not an edit target. Match its EXACT warm-white screens, dark evergreen accent #206853, ink #25332E, pastel icon containers, thin dividers, Chinese sans-serif typography, slim Android phone frames and elegant spacing. Create three NEW screens side by side on neutral white background. Small outside title "NAS Find / Android"; subtitle "文件操作 · 第二版概念"; outside labels beneath phones "04 文件操作", "05 图片预览", "06 多选". Large legible screens, no perspective, no callouts or marketing decoration.

SCREEN 04 FILE ACTION: Underlying search results look identical to reference screen 02: compact top row "家庭 NAS" and green dot "可搜索" plus gear; search "旅行", category chips, count "128 个结果 · 含子目录", file list. Dim the underlying screen behind a bottom sheet occupying lower 60%. Sheet top drag handle. Header custom small PDF outline icon inside blush square, name "旅行计划.pdf", full path "文档 / 旅行 / 旅行计划.pdf". Four action rows with consistent 24dp outline icons and spacious 56dp+ targets:
1 "用其他应用打开" with small secondary text "下载临时副本后打开"
2 "保存到手机" with small secondary text "选择保存位置"
3 "复制相对路径"
4 "在所在目录中搜索"
Subtle informational text below thin divider "本地副本的修改不会同步到 NAS".
Do NOT include Preview menu here: tapping file row already previews. No redundant outlined card around actions. Sheet white, no bright oversized CTA. No bottom navigation.

SCREEN 05 IMAGE PREVIEW: Full-screen in-app image viewer on warm offwhite canvas. Top Android status bar then compact toolbar: back arrow, "旅行照片.jpg" single line, save/download outline action and three-dot action. No NAS title, gear or connection indicator here. Under toolbar small muted path "照片 / 示例". Main viewport displays a tasteful large landscape photograph of green mountains, a calm lake and distant hazy peaks, realistic natural colors; letterboxed to preserve landscape aspect ratio, centered vertically without stretching. Below image a tiny secondary caption "示例图片". Only bottom system gesture bar, no thumbnails carousel, no persistent download footer, no pagination, no likes or share toolbar. Elegant photo viewing space.

SCREEN 06 MULTISELECT: Header context changes to close X at left, "已选 3 项" title, text action "全选" on right. No gear, NAS status or global nav during selection. Search field remains visible containing "旅行" but visually inactive. Count line "128 个结果 · 含子目录". List has leading rounded square checkboxes replacing file icons, no trailing kebab buttons. Seven rows with filename and relative directory, exactly THREE selected using checked dark-green boxes and pale green row background: "旅行计划.pdf" / "旅行预算.xlsx" / "旅行路线.pdf". Remaining "旅行日记.txt", "旅行照片.jpg", "旅行片段.mp4", "旅行素材" unchecked. Bottom CONTEXTUAL action bar only during selection, with two balanced labeled buttons "复制路径" and "导出清单", outline clipboard/export icons. This is an action bar not navigation; do not add search or settings tabs. Separate from gesture inset. No bulk file download.
Ensure all text legible and Chinese accurate. No app logos inside screens. Use custom generic file icons, no third party logos. No file sizes/dates in search list. Perfect consistency with reference visual system.
```

## 文件操作：精确修订

```text
Use case: precise-object-edit. Make ONLY two precise corrections to the RIGHTMOST screen "06 多选" of this reference image. Preserve both left screens exactly, and all other design typography spacing colors and device frames. Correction 1: replace its whole search-field row (including clear x and filter sliders button) with a single quiet non-interactive text summary on pale neutral background reading "旅行 · 全部类型". No magnifier, no x, no filters button, no text input affordance. This prevents changing the query while selecting. Correction 2: on each of its seven result rows REMOVE the colored file-type icon square, retaining only the existing checkbox at the left; shift filename and path together left to use that removed icon space. Exactly three boxes stay selected and three corresponding row backgrounds stay green: 旅行计划.pdf, 旅行预算.xlsx, 旅行路线.pdf. Keep top 已选 3 项, 全选, count 128 个结果 · 含子目录, all filenames, paths and bottom contextual actions. Nothing else changes.
```

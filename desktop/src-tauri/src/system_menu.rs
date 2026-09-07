//! Fetch paths asynchronously, then host the real Shell menu on the window's thread.
use crate::{config, decode, native, AppState};
use serde_json::{json, Value};
use std::sync::atomic::{AtomicBool, Ordering};
use tauri::State;
use win_context_menu::{init_com, ContextMenu, ShellItems};

static OPEN: AtomicBool = AtomicBool::new(false);
struct MenuGuard;
impl MenuGuard {
    fn acquire() -> Result<Self, String> {
        OPEN.compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
            .map_err(|_| "系统菜单正在处理中")?;
        Ok(Self)
    }
}
impl Drop for MenuGuard {
    fn drop(&mut self) {
        OPEN.store(false, Ordering::SeqCst);
    }
}

#[derive(Default)]
struct Paths {
    values: Vec<String>,
}
impl Paths {
    fn add(&mut self, path: &str) -> Result<(), String> {
        config::relative(path)?;
        self.values.push(path.into());
        Ok(())
    }
}

fn copy_as_path_text(paths: &[String]) -> Result<String, String> {
    let mut text = String::new();
    let mut bytes = 2; // UTF-16 terminator, as in the existing bulk clipboard limit.
    for path in paths {
        bytes += (path.encode_utf16().count() + 4) * 2; // quotes and CRLF
        if bytes > crate::bulk::CLIPBOARD_BYTES {
            return Err("路径文本超过剪贴板容量，请使用导出路径".into());
        }
        text.push('"');
        text.push_str(path);
        text.push_str("\"\r\n");
    }
    Ok(text)
}

#[tauri::command]
pub async fn show_system_menu(
    id: String,
    selection: Value,
    window: tauri::WebviewWindow,
    state: State<'_, AppState>,
) -> Result<Value, String> {
    let guard = MenuGuard::acquire()?;
    let session = state.session()?;
    let owner = window.hwnd().map_err(|_| "无法获取窗口")?.0 as isize;
    let mut point = windows_sys::Win32::Foundation::POINT { x: 0, y: 0 };
    unsafe {
        windows_sys::Win32::UI::WindowsAndMessaging::GetCursorPos(&mut point);
    }
    let mut paths = Paths::default();
    let mut cursor = 0;
    let mut expected = None;
    loop {
        let response = session
            .client
            .post(format!("{}/api/query/selection", session.config.server))
            .json(&json!({"id":id,"selection":selection,"cursor":cursor}))
            .send()
            .await
            .map_err(|_| "无法读取所选项目，请检查 NAS 连接")?;
        let page = decode(response).await?;
        let total = page["selected_total"].as_u64().ok_or("选择数据无效")?;
        if expected.is_some_and(|n| n != total) {
            return Err("查询结果发生变化，请重新选择".into());
        }
        expected = Some(total);
        for path in page["paths"].as_array().ok_or("路径数据无效")? {
            paths.add(path.as_str().ok_or("路径数据无效")?)?;
        }
        if page["done"] == true {
            break;
        }
        let next = page["next_cursor"].as_u64().ok_or("读取位置无效")?;
        if next <= cursor {
            return Err("无法完整读取选择，请重试".into());
        }
        cursor = next;
    }
    if paths.values.is_empty() {
        return Err("请先选择项目".into());
    }
    if Some(paths.values.len() as u64) != expected {
        return Err("选择未完整读取，未打开系统菜单".into());
    }

    let (send, receive) = std::sync::mpsc::sync_channel(1);
    window
        .run_on_main_thread(move || {
            let result = (|| -> Result<Value, String> {
                // Shell menu tracking and the owning window must share an input thread.
                let _guard = guard;
                let _com = init_com().map_err(|e| format!("无法初始化系统菜单：{e}"))?;
                let mapping = native::mapping(&session.config.drive);
                let resolved = paths
                    .values
                    .iter()
                    .map(|p| {
                        session
                            .config
                            .resolve(p, mapping.as_deref())
                            .map(|(p, _)| p)
                    })
                    .collect::<Result<Vec<_>, _>>()?;
                let items = ShellItems::from_paths(&resolved).map_err(|e| {
                    format!("无法读取所选项目的系统菜单：{e}")
                })?;
                let menu = ContextMenu::new(items)
                    .map_err(|e| format!("无法创建系统菜单：{e}"))?
                    .owner(owner)
                    .extended(true);
                let selected = menu
                    .show_at(point.x, point.y)
                    .map_err(|e| format!("无法显示系统菜单：{e}"))?;
                let invoked = selected.is_some();
                if let Some(item) = selected {
                    // The native Copy as path handler can silently produce an
                    // empty clipboard when CF_HDROP cannot render long UNC paths.
                    // Reuse our exact selected paths for this standard text action.
                    if item.menu_item().command_string.as_deref() == Some("copyaspath") {
                        native::clipboard(&copy_as_path_text(&resolved)?, false, owner)?;
                    } else {
                        item.execute().map_err(|e| format!("系统操作未完成：{e}"))?;
                    }
                }
                Ok(json!({"invoked":invoked}))
            })();
            let _ = send.send(result);
        })
        .map_err(|_| "无法调用系统菜单")?;
    tauri::async_runtime::spawn_blocking(move || receive.recv().map_err(|_| "系统菜单未能完成")?)
        .await
        .map_err(|_| "系统菜单未能完成")?
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn copy_as_path_preserves_long_unicode_paths_and_order() {
        let paths = vec![format!(r"\\server\share\{}\视频.mp4", "long".repeat(80)), r"Z:\another directory\短片.mp4".into()];
        let text = copy_as_path_text(&paths).unwrap();
        assert_eq!(text, format!("\"{}\"\r\n\"{}\"\r\n", paths[0], paths[1]));
        assert!(copy_as_path_text(&["x".repeat(crate::bulk::CLIPBOARD_BYTES / 2)]).is_err());
    }
    #[test]
    fn selection_accepts_cross_directory_without_a_count_ceiling() {
        let mut paths = Paths::default();
        for i in 0..2000 {
            paths.add(&format!("视频/片段 {i}.mp4")).unwrap();
        }
        assert_eq!(paths.values.len(), 2000);
        paths.add("另一个目录/片段.mp4").unwrap();
        assert_eq!(paths.values.len(), 2001);
        let mut root = Paths::default();
        root.add("a.mp4").unwrap();
        root.add("b.mp4").unwrap();
    }
    #[test]
    fn invalid_paths_do_not_reach_shell() {
        for path in [
            "../outside",
            "a/../b",
            "C:/file",
            "a\\b",
            "file:stream",
            "nul.txt",
            "",
        ] {
            assert!(Paths::default().add(path).is_err());
        }
    }
    #[test]
    fn repeated_request_guard_is_released_on_error() {
        let guard = MenuGuard::acquire().unwrap();
        assert!(MenuGuard::acquire().is_err());
        drop(guard);
        assert!(MenuGuard::acquire().is_ok());
    }
    #[test]
    #[ignore = "requires NAS_FIND_CLIENT_CONFIG and NAS_MENU_TEST_PATH; never invokes commands"]
    fn installed_shell_handlers_are_available_for_unc_and_drive() {
        let relative = std::env::var("NAS_MENU_TEST_PATH").unwrap();
        let config = config::live_config(None);
        let mapping = native::mapping(&config.drive);
        let _com = init_com().unwrap();
        for path in [
            config.resolve(&relative, mapping.as_deref()).unwrap().0,
            config.resolve(&relative, None).unwrap().0,
        ] {
            let menu = ContextMenu::new(ShellItems::from_path(path).unwrap()).unwrap();
            let labels: Vec<_> = menu
                .enumerate()
                .unwrap()
                .into_iter()
                .map(|i| i.label)
                .collect();
            assert!(!labels.is_empty(), "No Shell actions were returned");
            if let Ok(expected) = std::env::var("NAS_MENU_EXPECT_LABEL") {
                assert!(
                    labels.iter().any(|label| label.contains(&expected)),
                    "Expected Shell action missing"
                );
            }
            println!("Shell actions discovered for the configured shared file.");
        }
    }
}

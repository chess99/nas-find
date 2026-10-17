use crate::{config::Config, native, AppState};
use serde_json::{json, Value};
use std::ffi::{CStr, CString};
use std::sync::atomic::{AtomicBool, Ordering};
use tauri::State;

static OPEN: AtomicBool = AtomicBool::new(false);
struct MenuGuard;
impl MenuGuard {
    fn acquire() -> Result<Self, String> {
        OPEN.compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
            .map_err(|_| "文件菜单正在处理中")?;
        Ok(Self)
    }
}
impl Drop for MenuGuard {
    fn drop(&mut self) {
        OPEN.store(false, Ordering::SeqCst);
    }
}

extern "C" {
    fn nf_file_menu(
        window: *mut std::ffi::c_void,
        path: *const libc::c_char,
        items: *const libc::c_char,
        x: f64,
        y: f64,
    ) -> *mut libc::c_char;
}

fn file_target(config: &Config, path: &str) -> Result<(Config, String), String> {
    let (resolved, _) = config.resolve(path, None)?;
    let (source, _) = config.for_path(path)?;
    Ok((source, resolved))
}

pub async fn file_menu(
    path: String,
    items: Value,
    position: Option<Value>,
    window: tauri::WebviewWindow,
    state: State<'_, AppState>,
) -> Result<Value, String> {
    let guard = MenuGuard::acquire()?;
    let config = state.session()?.config;
    let (config, resolved) = tauri::async_runtime::spawn_blocking(move || {
        let (config, resolved) = file_target(&config, &path)?;
        native::verify_share(&config, &resolved)?;
        Ok::<_, String>((config, resolved))
    })
    .await
    .map_err(|_| "无法校验共享文件")??;
    let file = CString::new(resolved.as_str()).map_err(|_| "文件路径无效")?;
    let items = CString::new(items.to_string()).map_err(|_| "菜单参数无效")?;
    let coordinate = |key: &str| {
        position
            .as_ref()
            .and_then(|p| p[key].as_f64())
            .filter(|v| v.is_finite())
            .unwrap_or(-1.0)
    };
    let (x, y) = (coordinate("x"), coordinate("y"));
    let (send, receive) = std::sync::mpsc::sync_channel(1);
    let owner = window.clone();
    window
        .run_on_main_thread(move || {
            let _guard = guard;
            let result = (|| -> Result<Value, String> {
                let pointer = owner.ns_window().map_err(|_| "无法获取窗口")?;
                let output = unsafe { nf_file_menu(pointer, file.as_ptr(), items.as_ptr(), x, y) };
                if output.is_null() {
                    return Err("无法创建文件菜单".into());
                }
                let bytes = unsafe { CStr::from_ptr(output) }.to_bytes().to_vec();
                unsafe { libc::free(output.cast()) };
                serde_json::from_slice::<Value>(&bytes).map_err(|_| "文件菜单返回无效数据".into())
            })();
            let _ = send.send(result);
        })
        .map_err(|_| "无法显示文件菜单")?;
    let result = tauri::async_runtime::spawn_blocking(move || receive.recv())
        .await
        .map_err(|_| "文件菜单未能完成")?
        .map_err(|_| "文件菜单未能完成")??;
    if let Some(error) = result["error"].as_str() {
        return Err(error.into());
    }
    if let Some(application) = result["application"].as_str() {
        let application = application.to_owned();
        tauri::async_runtime::spawn_blocking(move || {
            // Recheck after the menu / application picker has been open.
            native::verify_share(&config, &resolved)?;
            native::open_with_application(&resolved, &application)
        })
        .await
        .map_err(|_| "打开文件未能完成")??;
        return Ok(json!({}));
    }
    Ok(result)
}

// Finder Sync extensions and Finder's own Quick Actions remain Finder-hosted.
#[tauri::command]
pub async fn show_system_menu() -> Result<(), String> {
    Err("请使用“打开所在位置”，在 Finder 中使用系统右键菜单".into())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn menu_target_uses_the_selected_shares_mount_and_verification_config() {
        let config = Config {
            mount_path: "/Volumes/legacy".into(),
            sources: ["data", "download"]
                .into_iter()
                .map(|id| crate::config::Source {
                    id: id.into(),
                    name: id.into(),
                    share: format!(r"\\nas-test\{id}"),
                    mount_path: format!("/Volumes/{id}"),
                })
                .collect(),
            ..crate::config::fixture()
        };
        for id in ["data", "download"] {
            let (source, resolved) = file_target(&config, &format!("{id}/same.mkv")).unwrap();
            assert_eq!(source.share, format!(r"\\nas-test\{id}"));
            assert_eq!(source.mount_path, format!("/Volumes/{id}"));
            assert!(source.sources.is_empty());
            assert_eq!(resolved, format!("/Volumes/{id}/same.mkv"));
            assert_eq!(file_target(&config, id).unwrap().1, source.mount_path);
        }
        for path in ["unknown/file.mkv", "data/../outside.mkv"] {
            assert!(file_target(&config, path).is_err());
        }
    }
}

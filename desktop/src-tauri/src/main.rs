#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
mod bulk;
mod config;
mod connection_share;
#[cfg(windows)]
mod native;
#[cfg(target_os = "macos")]
#[path = "native_macos.rs"]
mod native;
#[cfg(windows)]
mod system_menu;
#[cfg(target_os = "macos")]
#[path = "system_menu_macos.rs"]
mod system_menu;

use config::Config;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{path::PathBuf, sync::Mutex, time::Duration};
use tauri::{Manager, State};

#[derive(Clone, Default, Deserialize, Serialize)]
struct Stored {
    config: Config,
    password: Option<Vec<u8>>,
}
#[derive(Clone)]
struct Session {
    client: reqwest::Client,
    config: Config,
}
struct AppState {
    file: PathBuf,
    stored: Mutex<Stored>,
    session: Mutex<Option<Session>>,
    bulk: bulk::Jobs,
}

fn client() -> Result<reqwest::Client, String> {
    reqwest::Client::builder()
        // NAS connections are direct; system HTTP/SOCKS proxies may not honor LAN CIDR exclusions.
        .no_proxy()
        .cookie_store(true)
        .redirect(reqwest::redirect::Policy::none())
        .connect_timeout(Duration::from_secs(4))
        .timeout(Duration::from_secs(12))
        .build()
        .map_err(|_| "无法初始化连接".into())
}

async fn decode(response: reqwest::Response) -> Result<Value, String> {
    let status = response.status();
    if status.as_u16() == 401 {
        return Err("登录已失效，请重新连接".into());
    }
    if response
        .content_length()
        .is_some_and(|n| n > 4 * 1024 * 1024)
    {
        return Err("服务响应过大".into());
    }
    let value: Value = response
        .json()
        .await
        .map_err(|_| "服务响应格式不正确，请检查地址")?;
    if !status.is_success() {
        return Err(value["error"].as_str().unwrap_or("请求失败").into());
    }
    check_api_compatibility(&value)?;
    Ok(value)
}

fn check_api_compatibility(value: &Value) -> Result<(), String> {
    if value["min_client_api_version"].as_u64().unwrap_or(1) > 1 {
        return Err("服务器需要新版客户端，请更新 NAS Find".into());
    }
    if value["api_version"].as_u64().unwrap_or(1) < 1 {
        return Err("服务器接口版本过旧，请更新 NAS Find 服务".into());
    }
    Ok(())
}

async fn login(config: Config, password: String) -> Result<Session, String> {
    let client = client()?;
    let response = client
        .post(format!("{}/api/login", config.server))
        .json(&json!({"password": password}))
        .send()
        .await
        .map_err(|error| if error.is_timeout() { "连接 NAS 超时，请检查网络与服务是否运行" } else { "无法连接 NAS，请检查服务地址、网络与系统局域网权限" })?;
    if response.status().as_u16() == 401 {
        return Err("密码不正确".into());
    }
    decode(response).await?;
    Ok(Session { client, config })
}

impl AppState {
    fn session(&self) -> Result<Session, String> {
        self.session
            .lock()
            .unwrap()
            .clone()
            .ok_or("请先连接 NAS".into())
    }
    fn save(&self, stored: &Stored) -> Result<(), String> {
        let directory = self.file.parent().unwrap();
        std::fs::create_dir_all(directory).map_err(|_| "无法创建设置目录")?;
        let temp = self.file.with_extension("tmp");
        std::fs::write(&temp, serde_json::to_vec_pretty(stored).unwrap())
            .map_err(|_| "无法保存设置")?;
        std::fs::rename(temp, &self.file).map_err(|_| "无法替换设置文件")?;
        *self.stored.lock().unwrap() = stored.clone();
        Ok(())
    }
}

#[tauri::command]
fn bootstrap(state: State<AppState>) -> Value {
    let stored = state.stored.lock().unwrap();
    json!({"config":stored.config,"remembered":stored.password.is_some(),"platform":std::env::consts::OS,"version":env!("CARGO_PKG_VERSION")})
}

#[tauri::command]
async fn connect(
    config: Config,
    password: String,
    state: State<'_, AppState>,
) -> Result<Value, String> {
    let config = config.validated()?;
    let saved = state.stored.lock().unwrap().clone();
    let password = if password.is_empty() && saved.config.server == config.server {
        let encrypted = saved.password.as_ref().ok_or("请输入访问密码")?;
        String::from_utf8(native::protect(encrypted, true)?)
            .map_err(|_| "保存的密码不可用，请重新输入")?
    } else {
        password
    };
    if password.is_empty() {
        return Err("请输入访问密码".into());
    }
    let session = login(config.clone(), password.clone()).await?;
    let result = session
        .client
        .get(format!("{}/api/status", config.server))
        .send()
        .await
        .map_err(|_| "无法读取索引状态")?;
    let status = decode(result).await?;
    let encrypted = Some(native::protect(password.as_bytes(), false)?);
    state.save(&Stored {
        config: config.clone(),
        password: encrypted,
    })?;
    if let Some(previous) = saved.password.as_ref() {
        native::forget(previous);
    }
    *state.session.lock().unwrap() = Some(session);
    Ok(json!({"status":status,"config":config,"mapping":native::mapping(&config.drive)}))
}

#[tauri::command]
fn share_connection(state: State<'_, AppState>) -> Result<Value, String> {
    let session = state.session()?;
    let stored = state.stored.lock().unwrap();
    if stored.config.server != session.config.server {
        return Err("请先保存并连接当前 NAS".into());
    }
    connection_share::payload(&stored.config, stored.password.as_deref())
}

#[tauri::command]
async fn status(state: State<'_, AppState>) -> Result<Value, String> {
    let session = state.session()?;
    let response = session
        .client
        .get(format!("{}/api/status", session.config.server))
        .send()
        .await
        .map_err(|_| "NAS 连接暂时中断")?;
    decode(response).await
}

#[tauri::command]
async fn search(
    query: String,
    scope: String,
    extension: String,
    state: State<'_, AppState>,
) -> Result<Value, String> {
    let session = state.session()?;
    let response = session
        .client
        .get(format!("{}/api/search", session.config.server))
        .query(&[
            ("q", query.as_str()),
            ("scope", scope.as_str()),
            ("ext", extension.as_str()),
            ("limit", "200"),
        ])
        .send()
        .await
        .map_err(|_| "搜索失败，请检查 NAS 连接")?;
    decode(response).await
}

#[tauri::command]
async fn create_query(options: Value, state: State<'_, AppState>) -> Result<Value, String> {
    let session = state.session()?;
    let response = session
        .client
        .post(format!("{}/api/query", session.config.server))
        .json(&options)
        .send()
        .await
        .map_err(|_| "无法创建查询")?;
    decode(response).await
}

#[tauri::command]
async fn query_page(id: String, offset: u64, state: State<'_, AppState>) -> Result<Value, String> {
    let session = state.session()?;
    let response = session
        .client
        .get(format!("{}/api/query", session.config.server))
        .query(&[
            ("id", id),
            ("offset", offset.to_string()),
            ("limit", "500".into()),
        ])
        .send()
        .await
        .map_err(|_| "无法读取查询结果")?;
    decode(response).await
}

#[tauri::command]
async fn cancel_query(id: String, state: State<'_, AppState>) -> Result<(), String> {
    let session = state.session()?;
    let _ = session
        .client
        .post(format!("{}/api/query/cancel", session.config.server))
        .json(&json!({"id":id}))
        .send()
        .await;
    Ok(())
}

#[tauri::command]
async fn refresh_index(state: State<'_, AppState>) -> Result<Value, String> {
    let session = state.session()?;
    let response = session
        .client
        .post(format!("{}/api/refresh", session.config.server))
        .json(&json!({}))
        .send()
        .await
        .map_err(|_| "无法安排更新")?;
    decode(response).await
}

#[tauri::command]
async fn disconnect(state: State<'_, AppState>) -> Result<(), String> {
    let session = state.session.lock().unwrap().take();
    let mut saved = state.stored.lock().unwrap().clone();
    let previous = saved.password.take();
    state.save(&saved)?;
    if let Some(previous) = previous {
        native::forget(&previous);
    }
    if let Some(session) = session {
        let _ = session
            .client
            .post(format!("{}/api/logout", session.config.server))
            .json(&json!({}))
            .send()
            .await;
    }
    Ok(())
}

#[tauri::command]
async fn file_action(
    path: String,
    action: String,
    window: tauri::WebviewWindow,
    state: State<'_, AppState>,
) -> Result<Value, String> {
    let config = state.session()?.config;
    let owner = native::owner(&window)?;
    let result = tauri::async_runtime::spawn_blocking(move || {
        let mapped = native::mapping(&config.drive);
        let (resolved, fallback) = config.resolve(&path, mapped.as_deref())?;
        #[cfg(target_os = "macos")]
        if matches!(
            action.as_str(),
            "open" | "reveal" | "open_with" | "properties" | "copy_file"
        ) {
            native::verify_share(&config, &resolved)?;
        }
        match action.as_str() {
            "copy_path" => native::clipboard(&resolved, false, owner)?,
            "copy_unc" => native::clipboard(
                &format!("{}\\{}", config.share, config::relative(&path)?),
                false,
                owner,
            )?,
            "copy_file" => native::clipboard(&resolved, true, owner)?,
            "open" | "reveal" | "open_with" | "properties" => {
                native::shell_action(&resolved, &action, owner)?
            }
            _ => return Err("不支持的操作".to_string()),
        }
        Ok(json!({"path":resolved,"fallback":fallback}))
    })
    .await
    .map_err(|_| "系统操作未能完成")?;
    result
}

#[tauri::command]
async fn file_info(path: String, state: State<'_, AppState>) -> Result<Value, String> {
    config::relative_native(&path)?;
    let session = state.session()?;
    let response = session
        .client
        .get(format!("{}/api/info", session.config.server))
        .query(&[("path", path)])
        .send()
        .await
        .map_err(|_| "无法读取文件信息")?;
    decode(response).await
}

fn show(app: &tauri::AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.show();
        let _ = window.unminimize();
        let _ = window.set_focus();
    }
}

fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, _, _| show(app)))
        .setup(|app| {
            let file = app.path().app_config_dir()?.join("settings.json");
            let mut stored: Stored = std::fs::read(&file)
                .ok()
                .and_then(|b| serde_json::from_slice(&b).ok())
                .unwrap_or_default();
            // Explicit one-time migration from the existing deploy output; never bundle secrets.
            let args: Vec<String> = std::env::args().collect();
            if let Some(index) = args.iter().position(|s| s == "--import-connection") {
                let source = args.get(index + 1).ok_or("缺少连接文件路径")?;
                let value: Value = serde_json::from_slice(&std::fs::read(source)?)?;
                stored.config = Config::import_connection(&stored.config, &value)
                    .map_err(std::io::Error::other)?;
                stored.password = Some(
                    native::protect(
                        value["password"]
                            .as_str()
                            .ok_or("连接文件缺少密码")?
                            .as_bytes(),
                        false,
                    )
                    .map_err(std::io::Error::other)?,
                );
            }
            let state = AppState {
                file,
                stored: Mutex::new(stored.clone()),
                session: Mutex::new(None),
                bulk: Mutex::new(std::collections::HashMap::new()),
            };
            state.save(&stored).map_err(std::io::Error::other)?;
            app.manage(state);
            let open =
                tauri::menu::MenuItem::with_id(app, "show", "打开 NAS Find", true, None::<&str>)?;
            let quit = tauri::menu::MenuItem::with_id(app, "quit", "退出", true, None::<&str>)?;
            let menu = tauri::menu::Menu::with_items(app, &[&open, &quit])?;
            let mut icon = vec![0u8; 32 * 32 * 4];
            for (i, p) in icon.chunks_mut(4).enumerate() {
                let (x, y) = (i % 32, i / 32);
                p.copy_from_slice(
                    if (7..11).contains(&x)
                        || (21..25).contains(&x)
                        || ((x as i32 - y as i32).abs() < 3 && (7..25).contains(&x))
                    {
                        &[240, 248, 244, 255]
                    } else {
                        &[32, 104, 83, 255]
                    },
                );
            }
            tauri::tray::TrayIconBuilder::new()
                .icon(tauri::image::Image::new_owned(icon, 32, 32))
                .tooltip("NAS Find")
                .menu(&menu)
                .on_menu_event(|app, event| match event.id.as_ref() {
                    "show" => show(app),
                    "quit" => app.exit(0),
                    _ => {}
                })
                .on_tray_icon_event(|tray, event| {
                    if matches!(event, tauri::tray::TrayIconEvent::DoubleClick { .. }) {
                        show(tray.app_handle());
                    }
                })
                .build(app)?;
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            bootstrap,
            connect,
            share_connection,
            status,
            search,
            refresh_index,
            disconnect,
            file_action,
            file_info,
            create_query,
            query_page,
            cancel_query,
            bulk::start_bulk,
            bulk::bulk_status,
            bulk::cancel_bulk,
            bulk::reveal_export,
            system_menu::show_system_menu
        ])
        .build(tauri::generate_context!())
        .expect("NAS Find 启动失败")
        .run(|_app, _event| {
            #[cfg(target_os = "macos")]
            if matches!(_event, tauri::RunEvent::Reopen { .. }) {
                show(_app);
            }
        });
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    #[ignore = "requires NAS_FIND_ACCESS_FILE pointing to the local deploy credentials"]
    fn live_nas_search_and_smb_resolution() {
        tauri::async_runtime::block_on(async {
            let file = std::env::var("NAS_FIND_ACCESS_FILE").unwrap();
            let access: Value = serde_json::from_slice(&std::fs::read(file).unwrap()).unwrap();
            let config = config::live_config(Some(&access));
            let session = login(config.clone(), access["password"].as_str().unwrap().into())
                .await
                .unwrap();
            let status = decode(
                session
                    .client
                    .get(format!("{}/api/status", config.server))
                    .send()
                    .await
                    .unwrap(),
            )
            .await
            .unwrap();
            assert!(status["entries"].as_u64().unwrap() > 0);
            let result = decode(
                session
                    .client
                    .get(format!("{}/api/search", config.server))
                    .query(&[("q", "README"), ("scope", "code"), ("ext", "md")])
                    .send()
                    .await
                    .unwrap(),
            )
            .await
            .unwrap();
            let item = result["results"]
                .as_array()
                .unwrap()
                .iter()
                .find(|x| x["directory"] == false)
                .unwrap();
            let relative = item["path"].as_str().unwrap();
            let mapping = native::mapping(&config.drive);
            let (path, _) = config.resolve(relative, mapping.as_deref()).unwrap();
            #[cfg(target_os = "macos")]
            native::verify_share(&config, &path).unwrap();
            assert!(std::fs::metadata(path).unwrap().is_file());
            #[cfg(windows)]
            let unc = format!("{}\\{}", config.share, config::relative(relative).unwrap());
            #[cfg(windows)]
            assert!(std::fs::metadata(unc).unwrap().is_file());
            let excluded = decode(
                session
                    .client
                    .get(format!("{}/api/search", config.server))
                    .query(&[("q", "node_modules")])
                    .send()
                    .await
                    .unwrap(),
            )
            .await
            .unwrap();
            assert!(excluded["results"].as_array().unwrap().is_empty());
            println!("NAS login, search, exclusions, mapped file and UNC file access passed.");
        });
    }
}

#[cfg(test)]
mod protocol_tests {
    use super::*;
    #[test]
    fn product_versions_do_not_gate_compatible_protocols() {
        assert!(check_api_compatibility(&json!({})).is_ok());
        assert!(check_api_compatibility(&json!({"version":"9.0.0","api_version":2,"min_client_api_version":1})).is_ok());
        assert!(check_api_compatibility(&json!({"min_client_api_version":2})).is_err());
        assert!(check_api_compatibility(&json!({"api_version":0})).is_err());
    }
}

use crate::{config, decode, native, AppState, Session};
use serde_json::{json, Value};
use std::{
    collections::HashMap,
    io::Write,
    path::PathBuf,
    sync::{Arc, Mutex},
};
use tauri::{Manager, State};

const CLIPBOARD_BYTES: usize = 16 * 1024 * 1024;
pub struct Job {
    pub status: Mutex<Value>,
}
pub type Jobs = Mutex<HashMap<String, Arc<Job>>>;

fn cancelled(job: &Job) -> bool {
    job.status.lock().unwrap()["cancelled"] == true
}

#[tauri::command]
pub fn bulk_status(id: String, state: State<AppState>) -> Result<Value, String> {
    let job = state
        .bulk
        .lock()
        .unwrap()
        .get(&id)
        .cloned()
        .ok_or("复制任务已过期")?;
    let value = job.status.lock().unwrap().clone();
    Ok(value)
}

#[tauri::command]
pub fn cancel_bulk(id: String, state: State<AppState>) -> Result<(), String> {
    if let Some(job) = state.bulk.lock().unwrap().get(&id) {
        let mut value = job.status.lock().unwrap();
        if value["running"] == true {
            value["cancelled"] = json!(true);
        }
    }
    Ok(())
}

#[tauri::command]
pub async fn start_bulk(
    id: String,
    selection: Value,
    mode: String,
    unc: bool,
    quoted: bool,
    format: Option<String>,
    window: tauri::WebviewWindow,
    state: State<'_, AppState>,
) -> Result<Value, String> {
    if mode != "clipboard" && mode != "export" {
        return Err("无效操作".into());
    }
    let session = state.session()?;
    let csv = format.as_deref() == Some("csv");
    if csv && mode != "export" {
        return Err("CSV 清单请使用导出功能".into());
    }
    let owner = window.hwnd().map_err(|_| "无法读取窗口")?.0 as isize;
    let target = if mode == "export" {
        let folder = window
            .app_handle()
            .path()
            .download_dir()
            .map_err(|_| "无法获取下载目录")?
            .join("NAS Find");
        std::fs::create_dir_all(&folder).map_err(|_| "无法创建本机导出目录")?;
        let name = format!(
            "NAS路径清单-{}.{}",
            chrono::Local::now().format("%Y%m%d-%H%M%S-%f"),
            if csv { "csv" } else { "txt" }
        );
        Some(folder.join(name))
    } else {
        None
    };
    let key = format!(
        "{}-{}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    );
    let job = Arc::new(Job {
        status: Mutex::new(json!({"running":true,"cancelled":false,"processed":0,"total":0})),
    });
    {
        let mut jobs = state.bulk.lock().unwrap();
        if jobs
            .values()
            .any(|j| j.status.lock().unwrap()["running"] == true)
        {
            return Err("请先完成或取消正在进行的路径操作".into());
        }
        jobs.retain(|_, j| j.status.lock().unwrap()["running"] == true);
        jobs.insert(key.clone(), job.clone());
    }
    tauri::async_runtime::spawn(async move {
        let result = transfer(
            session,
            id,
            selection,
            unc,
            quoted,
            csv,
            target,
            owner,
            job.clone(),
        )
        .await;
        let mut value = job.status.lock().unwrap();
        if let Err(error) = result {
            value["error"] = json!(error);
        }
        value["running"] = json!(false);
    });
    Ok(json!({"id":key}))
}

#[tauri::command]
pub async fn reveal_export(
    id: String,
    window: tauri::WebviewWindow,
    state: State<'_, AppState>,
) -> Result<(), String> {
    let job = state
        .bulk
        .lock()
        .unwrap()
        .get(&id)
        .cloned()
        .ok_or("此导出任务已过期，请到下载目录的 NAS Find 文件夹查看")?;
    let path = job.status.lock().unwrap()["path"]
        .as_str()
        .ok_or("导出尚未完成")?
        .to_owned();
    let owner = window.hwnd().map_err(|_| "无法获取窗口")?.0 as isize;
    tauri::async_runtime::spawn_blocking(move || native::shell_action(&path, "reveal", owner))
        .await
        .map_err(|_| "无法打开导出位置")?
}

pub fn csv_line(config: &config::Config, path: &str, mapped: Option<&str>) -> String {
    let use_drive = config.prefer_drive
        && mapped.is_some_and(|m| m.trim_end_matches('\\').eq_ignore_ascii_case(&config.share));
    let windows = format!(
        "{}\\{}",
        if use_drive {
            &config.drive
        } else {
            &config.share
        },
        path.replace('/', "\\")
    );
    format!(
        "\"{}\",\"./{}\"\r\n",
        windows.replace('"', "\"\""),
        path.replace('"', "\"\"")
    )
}

pub fn line(
    config: &config::Config,
    path: &str,
    mapped: Option<&str>,
    quoted: bool,
) -> Result<String, String> {
    // Text export preserves Linux names. Opening a file still uses stricter Windows validation.
    if path.is_empty()
        || path.chars().any(|c| matches!(c, '\0' | '\r' | '\n' | '\\'))
        || path
            .split('/')
            .any(|p| p.is_empty() || p == "." || p == "..")
    {
        return Err("路径包含无法用逐行 Windows 路径表示的字符；未输出不完整清单".into());
    }
    let use_drive = config.prefer_drive
        && mapped.is_some_and(|m| m.trim_end_matches('\\').eq_ignore_ascii_case(&config.share));
    let path = format!(
        "{}\\{}",
        if use_drive {
            &config.drive
        } else {
            &config.share
        },
        path.replace('/', "\\")
    );
    Ok(if quoted {
        format!("\"{path}\"\r\n")
    } else {
        format!("{path}\r\n")
    })
}

async fn transfer(
    session: Session,
    id: String,
    selection: Value,
    unc: bool,
    quoted: bool,
    csv: bool,
    target: Option<PathBuf>,
    owner: isize,
    job: Arc<Job>,
) -> Result<(), String> {
    let mut config = session.config.clone();
    if unc {
        config.prefer_drive = false;
    }
    // Resolve mapping once for the whole operation, so one export cannot mix roots.
    let mapped = native::mapping(&config.drive);
    let fallback = config.prefer_drive
        && !mapped
            .as_deref()
            .is_some_and(|m| m.eq_ignore_ascii_case(&config.share));
    let mut temporary = match &target {
        Some(path) => Some(
            tempfile::NamedTempFile::new_in(path.parent().ok_or("保存位置无效")?)
                .map_err(|_| "无法创建临时导出文件")?,
        ),
        None => None,
    };
    if csv {
        if let Some(file) = temporary.as_mut() {
            file.write_all("\u{feff}Windows样式路径,NAS相对路径\r\n".as_bytes())
                .map_err(|_| "导出写入失败")?;
        }
    }
    let mut text = String::new();
    let mut utf16_bytes = 2usize;
    let (mut cursor, mut processed) = (0u64, 0u64);
    loop {
        if cancelled(&job) {
            return Err("已取消，原剪贴板与目标文件保持不变".into());
        }
        let response = session
            .client
            .post(format!("{}/api/query/selection", config.server))
            .json(&json!({"id":id,"selection":selection,"cursor":cursor}))
            .send()
            .await
            .map_err(|_| "路径读取中断，请重试；未写入不完整结果")?;
        let data = decode(response).await?;
        let paths = data["paths"].as_array().ok_or("路径响应无效")?;
        let mut chunk = String::new();
        for path in paths {
            let path = path.as_str().ok_or("路径响应无效")?;
            let row = if csv {
                csv_line(&config, path, mapped.as_deref())
            } else {
                match line(&config, path, mapped.as_deref(), quoted) {
                    Ok(line) => line,
                    Err(_) => {
                        job.status.lock().unwrap()["needs_csv"] = json!(true);
                        return Err(
                            "部分名称包含换行或反斜杠，请导出 CSV 完整清单；未跳过任何项目".into(),
                        );
                    }
                }
            };
            if target.is_none() {
                utf16_bytes += row.encode_utf16().count() * 2;
                if utf16_bytes > CLIPBOARD_BYTES {
                    job.status.lock().unwrap()["too_large"] = json!(true);
                    return Err("路径文本超过 16 MiB，请导出为 TXT；原剪贴板未改变".into());
                }
            }
            chunk.push_str(&row);
        }
        if let Some(file) = temporary.as_mut() {
            file.write_all(chunk.as_bytes())
                .map_err(|_| "导出写入失败，目标文件未改变")?;
        } else {
            text.push_str(&chunk);
        }
        processed += paths.len() as u64;
        {
            let mut value = job.status.lock().unwrap();
            value["processed"] = json!(processed);
            value["total"] = data["selected_total"].clone();
            value["fallback"] = json!(fallback);
        }
        if data["done"] == true {
            break;
        }
        let next = data["next_cursor"].as_u64().ok_or("读取位置无效")?;
        if next <= cursor {
            return Err("路径读取未前进，已停止以避免重复内容".into());
        }
        cursor = next;
    }
    if processed == 0 {
        return Err("没有选中的项目".into());
    }
    // Commit and cancellation are serialized. Before this point no destination is changed.
    let mut value = job.status.lock().unwrap();
    if value["cancelled"] == true {
        return Err("已取消，原剪贴板与目标文件保持不变".into());
    }
    if let (Some(mut file), Some(path)) = (temporary, target) {
        file.flush().map_err(|_| "导出文件写入失败")?;
        file.as_file().sync_all().map_err(|_| "导出文件保存失败")?;
        file.persist_noclobber(&path)
            .map_err(|_| "无法保存导出文件，未覆盖已有文件")?;
        value["path"] = json!(path.to_string_lossy());
    } else {
        native::clipboard(&text, false, owner)?;
    }
    value["running"] = json!(false);
    value["success"] = json!(true);
    Ok(())
}

#[cfg(test)]
mod tests {
    #[test]
    fn cancelled_export_removes_partial_file() {
        use std::io::{Read, Write};
        use std::sync::{Arc, Mutex};
        let folder = tempfile::tempdir().unwrap();
        let target = folder.path().join("complete.txt");
        let job = Arc::new(super::Job {
            status: Mutex::new(serde_json::json!({"running":true,"cancelled":false,"processed":0})),
        });
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let server_job = job.clone();
        let server = std::thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            stream
                .set_read_timeout(Some(std::time::Duration::from_secs(5)))
                .unwrap();
            let mut header = Vec::new();
            while !header.ends_with(b"\r\n\r\n") {
                let mut b = [0];
                stream.read_exact(&mut b).unwrap();
                header.push(b[0]);
            }
            let header = String::from_utf8(header).unwrap();
            let length: usize = header
                .lines()
                .find_map(|line| {
                    line.to_ascii_lowercase()
                        .strip_prefix("content-length:")
                        .map(|s| s.trim().parse().unwrap())
                })
                .unwrap();
            stream.read_exact(&mut vec![0; length]).unwrap();
            // Cancellation arrives while the first page is in flight.
            server_job.status.lock().unwrap()["cancelled"] = serde_json::json!(true);
            let body = r#"{"paths":["a.txt"],"selected_total":2,"next_cursor":1,"done":false}"#;
            write!(stream,"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",body.len(),body).unwrap();
        });
        let session = crate::Session {
            client: crate::client().unwrap(),
            config: crate::config::Config {
                server: format!("http://{address}"),
                ..crate::config::fixture()
            },
        };
        let result = tauri::async_runtime::block_on(super::transfer(
            session,
            "fixture".into(),
            serde_json::json!({"all":true,"ranges":[]}),
            false,
            false,
            false,
            Some(target.clone()),
            0,
            job.clone(),
        ));
        server.join().unwrap();
        assert!(result.unwrap_err().contains("取消"));
        assert_eq!(job.status.lock().unwrap()["processed"], 1);
        assert!(!target.exists());
        assert_eq!(std::fs::read_dir(folder.path()).unwrap().count(), 0);
    }

    #[test]
    fn bulk_lines_are_quoted_and_use_one_mapping() {
        let config = crate::config::fixture();
        assert_eq!(
            super::line(&config, "资料/a b.txt", Some(&config.share), true).unwrap(),
            "\"Z:\\资料\\a b.txt\"\r\n"
        );
        assert!(super::line(&config, "bad\nname.txt", None, false).is_err());
        assert_eq!(
            super::line(&config, "a.txt", None, false).unwrap(),
            "\\\\nas.example.internal\\files\\a.txt\r\n"
        );
        assert!(super::csv_line(&config, "a\nb\"c.txt", None).contains("\"./a\nb\"\"c.txt\""));
    }
}

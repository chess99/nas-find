// Finder extensions cannot be hosted using the Windows IContextMenu interface.
#[tauri::command]
pub async fn show_system_menu() -> Result<(), String> {
    Err("请使用“打开所在位置”，在 Finder 中使用系统右键菜单".into())
}

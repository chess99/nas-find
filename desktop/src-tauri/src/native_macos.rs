use std::{
    ffi::{CStr, CString},
    io::Write,
    process::{Command, Stdio},
};

pub fn owner(_: &tauri::WebviewWindow) -> Result<isize, String> {
    Ok(0)
}
pub fn mapping(_: &str) -> Option<String> {
    None
}

// settings.json contains only an opaque Keychain account reference.
pub fn protect(bytes: &[u8], decrypt: bool) -> Result<Vec<u8>, String> {
    const SERVICE: &str = "local.nas-find.desktop";
    if decrypt {
        let account = std::str::from_utf8(bytes).map_err(|_| "保存的凭据无效，请重新输入")?;
        security_framework::passwords::get_generic_password(SERVICE, account)
            .map_err(|_| "无法读取钥匙串中的登录凭据，请重新输入密码".into())
    } else {
        let account = format!(
            "login-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        );
        security_framework::passwords::set_generic_password(SERVICE, &account, bytes)
            .map_err(|_| "无法将登录凭据保存到钥匙串")?;
        Ok(account.into_bytes())
    }
}
pub fn forget(bytes: &[u8]) {
    if let Ok(account) = std::str::from_utf8(bytes) {
        let _ = security_framework::passwords::delete_generic_password(
            "local.nas-find.desktop",
            account,
        );
    }
}

pub fn verify_share(config: &crate::config::Config, path: &str) -> Result<(), String> {
    let directory = CString::new(config.mount_path.as_bytes()).map_err(|_| "挂载路径无效")?;
    let mut info = std::mem::MaybeUninit::<libc::statfs>::uninit();
    if unsafe { libc::statfs(directory.as_ptr(), info.as_mut_ptr()) } != 0 {
        return Err("共享尚未挂载，请在 Finder 中连接 SMB 服务器".into());
    }
    let info = unsafe { info.assume_init() };
    let fs = unsafe { CStr::from_ptr(info.f_fstypename.as_ptr()) }.to_string_lossy();
    let source = unsafe { CStr::from_ptr(info.f_mntfromname.as_ptr()) }.to_string_lossy();
    let mounted = unsafe { CStr::from_ptr(info.f_mntonname.as_ptr()) }.to_string_lossy();
    let expected = config
        .share
        .trim_start_matches('\\')
        .split('\\')
        .collect::<Vec<_>>();
    let source =
        url::Url::parse(&format!("smb:{source}")).map_err(|_| "此目录不是配置的 SMB 共享")?;
    let share = percent_encoding::percent_decode_str(source.path().trim_start_matches('/'))
        .decode_utf8_lossy();
    if fs != "smbfs"
        || expected.len() != 2
        || !source
            .host_str()
            .is_some_and(|h| h.eq_ignore_ascii_case(expected[0]))
        || !share.eq_ignore_ascii_case(expected[1])
        || std::fs::canonicalize(config.mount_path.as_str()).ok()
            != std::fs::canonicalize(mounted.as_ref()).ok()
    {
        return Err("挂载目录与配置的 SMB 共享不匹配，请检查 Finder 挂载位置和共享地址".into());
    }
    let root = std::fs::canonicalize(config.mount_path.as_str()).map_err(|_| "共享不可用")?;
    let file = std::fs::canonicalize(path).map_err(|_| "文件无法访问，可能已移动或共享离线")?;
    if !file.starts_with(root) {
        return Err("文件指向共享目录之外，已停止打开".into());
    }
    Ok(())
}

fn script(language: &str, script: &str, argument: &str) -> Result<(), String> {
    let result = Command::new("/usr/bin/osascript")
        .args(["-l", language, "-e", script, "--", argument])
        .output()
        .map_err(|_| "无法调用 macOS 文件操作")?;
    if !result.status.success() {
        return Err("macOS 文件操作失败，请检查共享是否可访问，以及系统设置中的自动化权限".into());
    }
    Ok(())
}

pub fn shell_action(path: &str, action: &str, _: isize) -> Result<(), String> {
    match action {
        "open" | "reveal" => {
            let mut command = Command::new("/usr/bin/open");
            if action == "reveal" {
                command.arg("-R");
            }
            let status = command
                .arg("--")
                .arg(path)
                .status()
                .map_err(|_| "无法打开文件")?;
            if status.success() {
                Ok(())
            } else {
                Err("文件无法打开，可能已移动或共享离线".into())
            }
        }
        "properties" => script(
            "AppleScript",
            r#"on run argv
    tell application "Finder"
        open information window of (POSIX file (item 1 of argv) as alias)
        activate
    end tell
end run"#,
            path,
        ),
        "open_with" => Err("请先在 Finder 中定位文件，再使用 Finder 的“打开方式”菜单".into()),
        _ => Err("不支持的文件操作".into()),
    }
}

pub fn clipboard(text: &str, file: bool, _: isize) -> Result<(), String> {
    if file {
        return script(
            "JavaScript",
            r#"ObjC.import('AppKit'); function run(argv) { const url = $.NSURL.fileURLWithPath($(argv[0])); const pasteboard = $.NSPasteboard.generalPasteboard; pasteboard.clearContents; if (!pasteboard.writeObjects($.NSArray.arrayWithObject(url))) throw Error('Clipboard failed'); }"#,
            text,
        );
    }
    let mut child = Command::new("/usr/bin/pbcopy")
        .env("LC_CTYPE", "UTF-8")
        .stdin(Stdio::piped())
        .spawn()
        .map_err(|_| "无法打开剪贴板")?;
    let result = child
        .stdin
        .take()
        .ok_or("剪贴板输入不可用")?
        .write_all(text.as_bytes());
    let status = child.wait().map_err(|_| "无法完成复制")?;
    if result.is_err() || !status.success() {
        return Err("复制失败，请重试".into());
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    #[test]
    fn local_directory_cannot_impersonate_smb_share() {
        let directory = tempfile::tempdir().unwrap();
        let config = crate::config::Config {
            mount_path: directory.path().to_string_lossy().into(),
            ..crate::config::fixture()
        };
        assert!(super::verify_share(&config, config.mount_path.as_str()).is_err());
    }
    #[test]
    #[ignore = "writes a temporary credential to the current user's Keychain"]
    fn keychain_round_trip_and_delete() {
        let secret = b"nas-find-synthetic-test";
        let reference = super::protect(secret, false).unwrap();
        assert_ne!(reference, secret);
        let restored = super::protect(&reference, true);
        super::forget(&reference);
        assert_eq!(restored.unwrap(), secret);
        assert!(super::protect(&reference, true).is_err());
    }
}

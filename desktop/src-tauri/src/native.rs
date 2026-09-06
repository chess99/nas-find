use std::{mem, ptr};
use windows_sys::Win32::{
    Foundation::*,
    NetworkManagement::WNet::WNetGetConnectionW,
    Security::Cryptography::*,
    System::{Com::*, DataExchange::*, Memory::*},
    UI::{Shell::*, WindowsAndMessaging::*},
};

fn wide(value: &str) -> Vec<u16> {
    value.encode_utf16().chain(Some(0)).collect()
}
fn error(action: &str) -> String {
    format!("{action}失败：{}", std::io::Error::last_os_error())
}

pub fn mapping(drive: &str) -> Option<String> {
    if drive.is_empty() {
        return None;
    }
    let name = wide(drive);
    let mut buf = vec![0u16; 32768];
    let mut size = buf.len() as u32;
    let code = unsafe { WNetGetConnectionW(name.as_ptr(), buf.as_mut_ptr(), &mut size) };
    if code != 0 {
        return None;
    }
    Some(String::from_utf16_lossy(
        &buf[..buf.iter().position(|c| *c == 0).unwrap_or(buf.len())],
    ))
}

pub fn protect(bytes: &[u8], decrypt: bool) -> Result<Vec<u8>, String> {
    let input = CRYPT_INTEGER_BLOB {
        cbData: bytes.len() as u32,
        pbData: bytes.as_ptr() as *mut u8,
    };
    let mut output: CRYPT_INTEGER_BLOB = unsafe { mem::zeroed() };
    let ok = unsafe {
        if decrypt {
            CryptUnprotectData(
                &input,
                ptr::null_mut(),
                ptr::null(),
                ptr::null_mut(),
                ptr::null(),
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut output,
            )
        } else {
            CryptProtectData(
                &input,
                ptr::null(),
                ptr::null(),
                ptr::null_mut(),
                ptr::null(),
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut output,
            )
        }
    };
    if ok == 0 {
        return Err("无法读取或保存此 Windows 用户的登录凭据，请重新输入密码".into());
    }
    let result =
        unsafe { std::slice::from_raw_parts(output.pbData, output.cbData as usize).to_vec() };
    unsafe {
        LocalFree(output.pbData as _);
    }
    Ok(result)
}

pub fn shell_action(path: &str, action: &str, owner: isize) -> Result<(), String> {
    // This function runs on a dedicated blocking thread; COM values never cross threads.
    let hr = unsafe {
        CoInitializeEx(
            ptr::null(),
            COINIT_APARTMENTTHREADED as u32 | COINIT_DISABLE_OLE1DDE as u32,
        )
    };
    if hr < 0 {
        return Err(format!("无法初始化系统文件操作（{hr:#x}）"));
    }
    let result = shell_inner(path, action, owner);
    unsafe {
        CoUninitialize();
    }
    result
}

fn shell_inner(path: &str, action: &str, owner: isize) -> Result<(), String> {
    let path = wide(path);
    unsafe {
        match action {
            "open" | "properties" => {
                let verb = wide(if action == "open" {
                    "open"
                } else {
                    "properties"
                });
                let mut info: SHELLEXECUTEINFOW = mem::zeroed();
                info.cbSize = mem::size_of_val(&info) as u32;
                info.fMask = SEE_MASK_INVOKEIDLIST | SEE_MASK_NOASYNC;
                info.hwnd = owner as HWND;
                info.lpVerb = verb.as_ptr();
                info.lpFile = path.as_ptr();
                info.nShow = SW_SHOWNORMAL;
                if ShellExecuteExW(&mut info) == 0 {
                    return Err(error("打开文件"));
                }
            }
            "reveal" => {
                let mut pidl = ptr::null_mut();
                let parsed = SHParseDisplayName(
                    path.as_ptr(),
                    ptr::null_mut(),
                    &mut pidl,
                    0,
                    ptr::null_mut(),
                );
                if parsed < 0 {
                    return Err(format!("文件无法定位，可能已移动或共享离线（{parsed:#x}）"));
                }
                let result = SHOpenFolderAndSelectItems(pidl, 0, ptr::null(), 0);
                CoTaskMemFree(pidl as _);
                if result < 0 {
                    return Err(format!("无法打开所在位置（{result:#x}）"));
                }
            }
            "open_with" => {
                let info = OPENASINFO {
                    pcszFile: path.as_ptr(),
                    pcszClass: ptr::null(),
                    oaifInFlags: OAIF_EXEC,
                };
                let result = SHOpenWithDialog(owner as HWND, &info);
                if result < 0 && result as u32 != 0x800704c7 {
                    return Err(format!("无法显示打开方式（{result:#x}）"));
                }
            }
            _ => return Err("不支持的文件操作".into()),
        }
    }
    Ok(())
}

pub fn clipboard(text: &str, file: bool, owner: isize) -> Result<(), String> {
    let mut data = Vec::<u8>::new();
    if file {
        // DROPFILES: pFiles, POINT, fNC, fWide. Unicode path list ends in two NULs.
        for n in [20u32, 0, 0, 0, 1] {
            data.extend_from_slice(&n.to_le_bytes());
        }
    }
    for c in wide(text) {
        data.extend_from_slice(&c.to_le_bytes());
    }
    if file {
        data.extend_from_slice(&[0, 0]);
    }
    unsafe {
        let handle = GlobalAlloc(GMEM_MOVEABLE, data.len());
        if handle.is_null() {
            return Err(error("准备剪贴板"));
        }
        let target = GlobalLock(handle);
        if target.is_null() {
            GlobalFree(handle);
            return Err(error("准备剪贴板"));
        }
        ptr::copy_nonoverlapping(data.as_ptr(), target as *mut u8, data.len());
        GlobalUnlock(handle);
        if OpenClipboard(owner as HWND) == 0 {
            GlobalFree(handle);
            return Err("剪贴板正被其他应用使用，请重试".into());
        }
        if EmptyClipboard() == 0 {
            CloseClipboard();
            GlobalFree(handle);
            return Err(error("更新剪贴板"));
        }
        let output = SetClipboardData(if file { 15 } else { 13 }, handle as _);
        CloseClipboard();
        if output.is_null() {
            GlobalFree(handle);
            return Err(error("复制"));
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    #[test]
    fn credentials_round_trip_without_plaintext_storage() {
        let original = b"nas-find-test-only";
        let encrypted = super::protect(original, false).unwrap();
        assert_ne!(encrypted, original);
        assert_eq!(super::protect(&encrypted, true).unwrap(), original);
        assert!(super::protect(b"invalid", true).is_err());
    }
}

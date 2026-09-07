//! Internal string-conversion helpers for Windows wide-string (UTF-16) APIs.

use std::path::{Path, PathBuf};
use windows::core::PCWSTR;

/// Strip the `\\?\` extended-path prefix that `std::fs::canonicalize` adds on
/// Windows. `SHParseDisplayName` does not accept this prefix.
pub(crate) fn strip_extended_prefix(path: &Path) -> PathBuf {
    let s = path.to_string_lossy();
    if let Some(stripped) = s.strip_prefix(r"\\?\UNC\") {
        PathBuf::from(format!(r"\\{}", stripped))
    } else if let Some(stripped) = s.strip_prefix(r"\\?\") {
        PathBuf::from(stripped)
    } else {
        path.to_path_buf()
    }
}

/// Convert a `Path` to a null-terminated wide string (UTF-16) suitable for
/// Windows API calls.
pub(crate) fn path_to_wide(path: &Path) -> Vec<u16> {
    use std::os::windows::ffi::OsStrExt;
    path.as_os_str()
        .encode_wide()
        .chain(std::iter::once(0))
        .collect()
}

/// Create a `PCWSTR` from a wide string buffer.
///
/// The returned `PCWSTR` borrows from `buf`; the caller must ensure `buf`
/// outlives the pointer.
pub(crate) fn wide_to_pcwstr(buf: &[u16]) -> PCWSTR {
    PCWSTR(buf.as_ptr())
}

/// Convert a null-terminated `&[u8]` (ANSI) buffer to a Rust `String`,
/// stopping at the first NUL byte.
pub(crate) fn ansi_buf_to_string(buf: &[u8]) -> String {
    let end = buf.iter().position(|&b| b == 0).unwrap_or(buf.len());
    String::from_utf8_lossy(&buf[..end]).into_owned()
}

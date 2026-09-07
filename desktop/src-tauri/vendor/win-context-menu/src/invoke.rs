//! Command invocation via `IContextMenu::InvokeCommand`.

use windows::Win32::Foundation::HWND;
use windows::Win32::UI::Shell::CMINVOKECOMMANDINFOEX;
use windows::Win32::UI::WindowsAndMessaging::SW_SHOWNORMAL;
use windows::core::PCSTR;

use crate::error::{Error, Result};
use crate::menu_items::InvokeParams;

/// `CMIC_MASK_UNICODE` — tells the shell to prefer the wide-string fields
/// (`lpVerbW`, `lpDirectoryW`, etc.) over the ANSI ones. Not re-exported by
/// the `windows` crate as of 0.58, so we define it manually.
const CMIC_MASK_UNICODE: u32 = 0x00004000;

/// Invoke a context menu command by its zero-based offset from `ID_FIRST`.
///
/// The offset is converted to an `MAKEINTRESOURCE`-style pseudo-pointer
/// (i.e., a small integer cast to a pointer) and placed in both `lpVerb` and
/// `lpVerbW` of `CMINVOKECOMMANDINFOEX`.
pub(crate) fn invoke_command(
    ctx_menu: &windows::Win32::UI::Shell::IContextMenu,
    command_offset: u32,
    hwnd: HWND,
    params: Option<InvokeParams>,
) -> Result<()> {
    let show_cmd = params
        .as_ref()
        .and_then(|p| p.show_cmd)
        .unwrap_or(SW_SHOWNORMAL.0);

    let directory_wide: Option<Vec<u16>> = params
        .as_ref()
        .and_then(|p| p.directory.as_ref())
        .map(|d| d.encode_utf16().chain(std::iter::once(0)).collect());

    // Per the IContextMenu::InvokeCommand documentation, when specifying a
    // command by its menu-identifier offset (rather than by verb string), the
    // offset is placed directly into the pointer field using the
    // MAKEINTRESOURCEA / MAKEINTRESOURCEW pattern — i.e., the integer value is
    // cast to a pointer type. The shell checks whether the high-order word is
    // zero to distinguish this from an actual string pointer.
    let verb = command_offset as usize;
    let verb_w = command_offset as usize;

    let mut info = CMINVOKECOMMANDINFOEX {
        cbSize: std::mem::size_of::<CMINVOKECOMMANDINFOEX>() as u32,
        fMask: CMIC_MASK_UNICODE,
        hwnd,
        // SAFETY: `MAKEINTRESOURCE`-style cast — the shell inspects the
        // high-order word and treats values < 0x10000 as integer offsets,
        // not as string pointers. This is the documented usage pattern.
        lpVerb: PCSTR(verb as *const u8),
        lpVerbW: windows::core::PCWSTR(verb_w as *const u16),
        nShow: show_cmd,
        ..Default::default()
    };

    if let Some(ref dir) = directory_wide {
        info.lpDirectoryW = windows::core::PCWSTR(dir.as_ptr());
    }

    // SAFETY: `info` is a fully initialized `CMINVOKECOMMANDINFOEX` with a
    // valid `cbSize`. The `ctx_menu` was obtained from a prior successful
    // `QueryContextMenu` call and is still valid. `hwnd` is either a valid
    // window handle or the hidden window created by this crate.
    unsafe {
        ctx_menu
            .InvokeCommand(std::ptr::addr_of!(info) as *const _)
            .map_err(Error::InvokeCommand)?;
    }

    Ok(())
}

/// Invoke a context menu command by its verb string (e.g. "paste", "delete").
pub(crate) fn invoke_command_by_verb(
    ctx_menu: &windows::Win32::UI::Shell::IContextMenu,
    verb: &str,
    hwnd: HWND,
) -> Result<()> {
    let verb_ansi: Vec<u8> = verb.bytes().chain(std::iter::once(0)).collect();
    let verb_wide: Vec<u16> = verb.encode_utf16().chain(std::iter::once(0)).collect();

    let info = CMINVOKECOMMANDINFOEX {
        cbSize: std::mem::size_of::<CMINVOKECOMMANDINFOEX>() as u32,
        fMask: CMIC_MASK_UNICODE,
        hwnd,
        lpVerb: PCSTR(verb_ansi.as_ptr()),
        lpVerbW: windows::core::PCWSTR(verb_wide.as_ptr()),
        nShow: SW_SHOWNORMAL.0,
        ..Default::default()
    };

    // SAFETY: Same as invoke_command — `info` is fully initialized with valid
    // verb string pointers (not MAKEINTRESOURCE).
    unsafe {
        ctx_menu
            .InvokeCommand(std::ptr::addr_of!(info) as *const _)
            .map_err(Error::InvokeCommand)?;
    }

    Ok(())
}

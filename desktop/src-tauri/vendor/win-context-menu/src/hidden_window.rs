//! Hidden owner window for submenu rendering.
//!
//! Windows Shell context menus with submenus need a window that can handle
//! `WM_INITMENUPOPUP`, `WM_DRAWITEM`, and `WM_MEASUREITEM` messages. This
//! module creates an invisible 0×0 popup window with a custom `WndProc` that
//! forwards these messages to `IContextMenu2::HandleMenuMsg` or
//! `IContextMenu3::HandleMenuMsg2`.

use std::sync::Once;

use windows::Win32::Foundation::{HWND, LPARAM, LRESULT, WPARAM};
use windows::Win32::UI::Shell::{IContextMenu2, IContextMenu3};
use windows::Win32::UI::WindowsAndMessaging::*;

use crate::error::{Error, Result};

static REGISTER_CLASS: Once = Once::new();

fn class_name() -> windows::core::PCWSTR {
    static CLASS_NAME_WIDE: std::sync::LazyLock<Vec<u16>> = std::sync::LazyLock::new(|| {
        "WinContextMenuHidden"
            .encode_utf16()
            .chain(std::iter::once(0))
            .collect()
    });
    windows::core::PCWSTR(CLASS_NAME_WIDE.as_ptr())
}

/// Data stored in `GWLP_USERDATA` for the hidden window.
pub(crate) struct WndProcData {
    pub ctx_menu2: Option<IContextMenu2>,
    pub ctx_menu3: Option<IContextMenu3>,
}

/// A hidden 0×0 popup window used as the owner for `TrackPopupMenu`.
///
/// This is necessary for proper submenu rendering with owner-drawn items.
/// The window's `WndProc` forwards menu messages to `IContextMenu2`/`IContextMenu3`.
pub(crate) struct HiddenWindow {
    pub hwnd: HWND,
}

impl HiddenWindow {
    pub fn new() -> Result<Self> {
        let cn = class_name();

        REGISTER_CLASS.call_once(|| {
            let wc = WNDCLASSEXW {
                cbSize: std::mem::size_of::<WNDCLASSEXW>() as u32,
                lpfnWndProc: Some(hidden_wnd_proc),
                lpszClassName: cn,
                ..Default::default()
            };
            // SAFETY: Registering a window class with a valid WNDCLASSEXW.
            // We only do this once (guarded by `Once`).
            unsafe {
                RegisterClassExW(&wc);
            }
        });

        // SAFETY: Creating a zero-sized popup window with our registered
        // class. The window is never shown and exists solely to own the
        // popup menu and receive forwarded messages.
        let hwnd = unsafe {
            CreateWindowExW(
                WINDOW_EX_STYLE::default(),
                cn,
                windows::core::PCWSTR::null(),
                WS_POPUP,
                0,
                0,
                0,
                0,
                None,
                None,
                None,
                None,
            )
            .map_err(Error::CreateWindow)?
        };

        Ok(Self { hwnd })
    }

    /// Store `IContextMenu2`/`IContextMenu3` pointers in the window's user
    /// data so the `WndProc` can forward submenu messages.
    pub fn set_context_menu_handlers(
        &self,
        ctx2: Option<IContextMenu2>,
        ctx3: Option<IContextMenu3>,
    ) {
        let data = Box::new(WndProcData {
            ctx_menu2: ctx2,
            ctx_menu3: ctx3,
        });
        // SAFETY: `self.hwnd` is a valid window we own. We store a raw pointer
        // to a heap-allocated `WndProcData`. The pointer is freed in
        // `clear_context_menu_handlers` (called from `Drop`).
        unsafe {
            SetWindowLongPtrW(self.hwnd, GWLP_USERDATA, Box::into_raw(data) as isize);
        }
    }

    /// Clear the stored context menu handlers and free the associated memory.
    pub fn clear_context_menu_handlers(&self) {
        // SAFETY: We read the pointer we previously stored. If non-null, we
        // reclaim the `Box` to free it. Setting the value to 0 prevents
        // double-free.
        let ptr = unsafe { GetWindowLongPtrW(self.hwnd, GWLP_USERDATA) };
        if ptr != 0 {
            // SAFETY: `ptr` is a valid `Box<WndProcData>` that we allocated
            // in `set_context_menu_handlers`. We reclaim it here to free it
            // and zero out the userdata to prevent double-free.
            unsafe {
                let _ = Box::from_raw(ptr as *mut WndProcData);
                SetWindowLongPtrW(self.hwnd, GWLP_USERDATA, 0);
            }
        }
    }
}

impl Drop for HiddenWindow {
    fn drop(&mut self) {
        self.clear_context_menu_handlers();
        // SAFETY: `self.hwnd` is a valid window handle created in `new()`.
        unsafe {
            let _ = DestroyWindow(self.hwnd);
        }
    }
}

/// Window procedure that forwards menu messages to `IContextMenu2`/`IContextMenu3`.
///
/// The shell uses owner-drawn menu items for things like icons and custom
/// rendering. Without forwarding these messages, submenus would appear blank
/// or fail to open.
///
/// # Safety
///
/// Called by the Windows message loop. `hwnd` is guaranteed valid by the OS.
/// `GWLP_USERDATA` is either 0 (no handlers set) or a valid `*mut WndProcData`
/// set by `set_context_menu_handlers`.
unsafe extern "system" fn hidden_wnd_proc(
    hwnd: HWND,
    msg: u32,
    wparam: WPARAM,
    lparam: LPARAM,
) -> LRESULT {
    if matches!(msg, WM_INITMENUPOPUP | WM_DRAWITEM | WM_MEASUREITEM | WM_MENUCHAR) {
        // SAFETY: We only store either 0 or a valid `Box<WndProcData>`
        // pointer via `SetWindowLongPtrW`. We read it here without taking
        // ownership (no `Box::from_raw`), so no double-free.
        let ptr = unsafe { GetWindowLongPtrW(hwnd, GWLP_USERDATA) };
        if ptr != 0 {
            // SAFETY: `ptr` is a valid `*const WndProcData` set by
            // `set_context_menu_handlers`. We only read through it.
            let data = unsafe { &*(ptr as *const WndProcData) };

            // Prefer IContextMenu3 (supports WM_MENUCHAR too) over IContextMenu2.
            if let Some(ref ctx3) = data.ctx_menu3 {
                let mut result = LRESULT(0);
                // SAFETY: COM method call on a valid interface obtained from
                // a successful `QueryInterface` (`.cast()`) earlier.
                if unsafe {
                    ctx3.HandleMenuMsg2(msg, wparam, lparam, Some(&mut result as *mut _))
                }
                .is_ok()
                {
                    return result;
                }
            }

            if let Some(ref ctx2) = data.ctx_menu2 {
                // SAFETY: Same as above — valid COM interface.
                if unsafe { ctx2.HandleMenuMsg(msg, wparam, lparam) }.is_ok() {
                    return LRESULT(0);
                }
            }
        }
    }

    // SAFETY: Default window procedure for unhandled messages.
    unsafe { DefWindowProcW(hwnd, msg, wparam, lparam) }
}

//! C-compatible FFI layer for `win-context-menu`.
//!
//! Enable with the **`ffi`** feature flag. All functions in this module use
//! C calling conventions and return null pointers on failure. Error details
//! can be retrieved via [`wcm_last_error`].
//!
//! # Threading
//!
//! All functions **must** be called from a thread that has been initialized for
//! COM STA mode (via [`wcm_com_init`]). These functions are **not thread-safe**.
//!
//! # Memory ownership
//!
//! Pointers returned by `wcm_*` functions are heap-allocated and must be freed
//! by the corresponding `wcm_free_*` function. Failing to do so will leak
//! memory.

use std::cell::RefCell;
use std::ffi::{CStr, CString};
use std::os::raw::c_char;
use std::path::PathBuf;

use crate::com::ComGuard;
use crate::context_menu::ContextMenu;
use crate::menu_items::MenuItem;
use crate::shell_item::ShellItems;

thread_local! {
    static LAST_ERROR: RefCell<Option<CString>> = const { RefCell::new(None) };
}

fn set_last_error(err: &crate::error::Error) {
    let msg = CString::new(err.to_string()).unwrap_or_default();
    LAST_ERROR.with(|e| {
        *e.borrow_mut() = Some(msg);
    });
}

/// Opaque handle for a COM guard.
pub struct FfiComGuard(ComGuard);

/// Information about a selected context menu item.
///
/// Returned by [`wcm_show_context_menu`]. Must be freed with
/// [`wcm_free_selected`].
#[repr(C)]
pub struct FfiSelectedItem {
    /// The command ID of the selected item.
    pub command_id: u32,
    /// The display label (UTF-8, heap-allocated). May be null.
    pub label: *mut c_char,
    /// The verb string (UTF-8, heap-allocated). Null if unavailable.
    pub verb: *mut c_char,
}

/// Retrieve the last error message from the most recent failed `wcm_*` call
/// on this thread.
///
/// Returns a pointer to a static thread-local C string, or null if no error
/// has occurred. The pointer is valid until the next `wcm_*` call on the same
/// thread. **Do not free this pointer.**
#[no_mangle]
pub extern "C" fn wcm_last_error() -> *const c_char {
    LAST_ERROR.with(|e| {
        e.borrow()
            .as_ref()
            .map(|s| s.as_ptr())
            .unwrap_or(std::ptr::null())
    })
}

/// Initialize COM in STA mode.
///
/// Must be called once per thread before any other `wcm_*` function.
/// Returns an opaque handle that must be freed with [`wcm_com_uninit`].
/// Returns null on failure (call [`wcm_last_error`] for details).
#[no_mangle]
pub extern "C" fn wcm_com_init() -> *mut FfiComGuard {
    match ComGuard::new() {
        Ok(guard) => Box::into_raw(Box::new(FfiComGuard(guard))),
        Err(e) => {
            set_last_error(&e);
            std::ptr::null_mut()
        }
    }
}

/// Free a COM guard handle returned by [`wcm_com_init`].
///
/// # Safety
///
/// `guard` must be a pointer returned by [`wcm_com_init`], or null.
/// Must not be called more than once for the same pointer.
#[no_mangle]
pub unsafe extern "C" fn wcm_com_uninit(guard: *mut FfiComGuard) {
    if !guard.is_null() {
        // SAFETY: Caller guarantees `guard` was returned by `wcm_com_init`
        // and has not been freed yet.
        let _ = unsafe { Box::from_raw(guard) };
    }
}

/// Show a context menu for a single file path at the given screen coordinates.
///
/// Returns a [`FfiSelectedItem`] pointer if the user selected an item, or null
/// if the menu was dismissed or an error occurred. On error, call
/// [`wcm_last_error`] for details.
///
/// The returned pointer must be freed with [`wcm_free_selected`]. The
/// selected command is automatically executed before this function returns.
///
/// # Safety
///
/// - `path` must be a valid null-terminated UTF-8 C string, or null.
/// - Must be called from a thread with an active `wcm_com_init` guard.
#[no_mangle]
pub unsafe extern "C" fn wcm_show_context_menu(
    path: *const c_char,
    x: i32,
    y: i32,
) -> *mut FfiSelectedItem {
    if path.is_null() {
        return std::ptr::null_mut();
    }

    // SAFETY: Caller guarantees `path` is a valid null-terminated C string.
    let path_str = match unsafe { CStr::from_ptr(path) }.to_str() {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };

    let items = match ShellItems::from_path(PathBuf::from(path_str)) {
        Ok(i) => i,
        Err(e) => {
            set_last_error(&e);
            return std::ptr::null_mut();
        }
    };

    let menu = match ContextMenu::new(items) {
        Ok(m) => m,
        Err(e) => {
            set_last_error(&e);
            return std::ptr::null_mut();
        }
    };

    match menu.show_at(x, y) {
        Ok(Some(selected)) => {
            let label = CString::new(selected.menu_item().label.as_str())
                .unwrap_or_default()
                .into_raw();
            let verb = selected
                .menu_item()
                .command_string
                .as_deref()
                .and_then(|v| CString::new(v).ok())
                .map(|c| c.into_raw())
                .unwrap_or(std::ptr::null_mut());
            let command_id = selected.command_id();

            // Execute the command
            if let Err(e) = selected.execute() {
                set_last_error(&e);
            }

            Box::into_raw(Box::new(FfiSelectedItem {
                command_id,
                label,
                verb,
            }))
        }
        Ok(None) => std::ptr::null_mut(),
        Err(e) => {
            set_last_error(&e);
            std::ptr::null_mut()
        }
    }
}

/// Free a [`FfiSelectedItem`] returned by [`wcm_show_context_menu`].
///
/// # Safety
///
/// `item` must be a pointer returned by [`wcm_show_context_menu`], or null.
/// Must not be called more than once for the same pointer.
#[no_mangle]
pub unsafe extern "C" fn wcm_free_selected(item: *mut FfiSelectedItem) {
    if !item.is_null() {
        // SAFETY: Caller guarantees `item` was returned by
        // `wcm_show_context_menu` and has not been freed yet.
        let item = unsafe { Box::from_raw(item) };
        if !item.label.is_null() {
            // SAFETY: `label` was allocated by `CString::into_raw`.
            let _ = unsafe { CString::from_raw(item.label) };
        }
        if !item.verb.is_null() {
            // SAFETY: `verb` was allocated by `CString::into_raw`.
            let _ = unsafe { CString::from_raw(item.verb) };
        }
    }
}

/// Enumerate context menu items as a JSON string.
///
/// Returns a heap-allocated null-terminated UTF-8 C string containing a JSON
/// array, or null on failure (call [`wcm_last_error`] for details). The
/// returned pointer must be freed with [`wcm_free_string`].
///
/// # Safety
///
/// - `path` must be a valid null-terminated UTF-8 C string, or null.
/// - Must be called from a thread with an active `wcm_com_init` guard.
#[no_mangle]
pub unsafe extern "C" fn wcm_enumerate_menu(
    path: *const c_char,
    extended: bool,
) -> *mut c_char {
    if path.is_null() {
        return std::ptr::null_mut();
    }

    // SAFETY: Caller guarantees `path` is a valid null-terminated C string.
    let path_str = match unsafe { CStr::from_ptr(path) }.to_str() {
        Ok(s) => s,
        Err(_) => return std::ptr::null_mut(),
    };

    let items = match ShellItems::from_path(PathBuf::from(path_str)) {
        Ok(i) => i,
        Err(e) => {
            set_last_error(&e);
            return std::ptr::null_mut();
        }
    };

    let menu = match ContextMenu::new(items) {
        Ok(m) => m.extended(extended),
        Err(e) => {
            set_last_error(&e);
            return std::ptr::null_mut();
        }
    };

    match menu.enumerate() {
        Ok(items) => {
            let json = menu_items_to_json(&items);
            match CString::new(json) {
                Ok(c) => c.into_raw(),
                Err(_) => std::ptr::null_mut(),
            }
        }
        Err(e) => {
            set_last_error(&e);
            std::ptr::null_mut()
        }
    }
}

/// Free a string returned by [`wcm_enumerate_menu`].
///
/// # Safety
///
/// `s` must be a pointer returned by a `wcm_*` function, or null.
#[no_mangle]
pub unsafe extern "C" fn wcm_free_string(s: *mut c_char) {
    if !s.is_null() {
        // SAFETY: Caller guarantees `s` was allocated by `CString::into_raw`.
        let _ = unsafe { CString::from_raw(s) };
    }
}

/// Serialize menu items to a JSON array string.
fn menu_items_to_json(items: &[MenuItem]) -> String {
    #[derive(serde::Serialize)]
    struct JsonMenuItem<'a> {
        id: u32,
        label: &'a str,
        #[serde(skip_serializing_if = "Option::is_none")]
        verb: Option<&'a str>,
        separator: bool,
        disabled: bool,
        #[serde(skip_serializing_if = "Option::is_none")]
        submenu: Option<Vec<JsonMenuItem<'a>>>,
    }

    fn to_json_items(items: &[MenuItem]) -> Vec<JsonMenuItem<'_>> {
        items
            .iter()
            .map(|item| JsonMenuItem {
                id: item.id,
                label: &item.label,
                verb: item.command_string.as_deref(),
                separator: item.is_separator,
                disabled: item.is_disabled,
                submenu: item.submenu.as_ref().map(|s| to_json_items(s)),
            })
            .collect()
    }

    serde_json::to_string(&to_json_items(items)).unwrap_or_else(|_| "[]".to_string())
}

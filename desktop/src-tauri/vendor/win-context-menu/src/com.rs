//! COM initialization guard and PIDL memory management.
//!
//! Windows Shell APIs require COM to be initialized on the calling thread in
//! single-threaded apartment (STA) mode. The types in this module provide RAII
//! wrappers so that COM lifetime and PIDL memory are automatically managed.

use crate::error::{Error, Result};
use windows::Win32::System::Com::CoTaskMemFree;
use windows::Win32::System::Ole::{OleFlushClipboard, OleInitialize, OleUninitialize};
use windows::Win32::UI::Shell::Common::ITEMIDLIST;

/// RAII guard for COM initialization. Calls `CoUninitialize` on drop.
///
/// # Threading
///
/// This guard initializes COM in **single-threaded apartment (STA)** mode,
/// which is required by the Windows Shell context menu interfaces. All context
/// menu operations must happen on the same thread that created the `ComGuard`.
///
/// `ComGuard` is intentionally **not `Send` or `Sync`** — moving it to another
/// thread would violate STA rules.
///
/// # Example
///
/// ```no_run
/// let _com = win_context_menu::init_com()?;
/// // COM is active for the lifetime of `_com`
/// # Ok::<(), win_context_menu::Error>(())
/// ```
pub struct ComGuard {
    // prevent Send + Sync by holding a non-Send type
    _not_send: std::marker::PhantomData<*mut ()>,
}

impl ComGuard {
    /// Initialize COM in single-threaded apartment mode.
    ///
    /// Returns [`Error::ComInit`] if COM has already been initialized with an
    /// incompatible threading model on this thread.
    pub fn new() -> Result<Self> {
        // SAFETY: `OleInitialize` initializes COM in STA mode **and** the OLE
        // clipboard / drag-drop subsystem. We need the OLE clipboard so that
        // shell copy/paste (which uses `OleSetClipboard` internally) works
        // correctly and `OleFlushClipboard` can persist data after process exit.
        // Calling once per thread is safe; redundant calls return S_FALSE and
        // are balanced by `OleUninitialize` in `Drop`.
        unsafe {
            OleInitialize(None).map_err(Error::ComInit)?;
        }
        Ok(Self {
            _not_send: std::marker::PhantomData,
        })
    }
}

impl Drop for ComGuard {
    fn drop(&mut self) {
        // SAFETY: `OleFlushClipboard` converts any OLE clipboard data object
        // into a static snapshot so that clipboard content (e.g. file copy)
        // survives after this process exits. Safe to call even when no OLE
        // clipboard data is present — it simply returns S_OK.
        unsafe {
            let _ = OleFlushClipboard();
        }
        // SAFETY: Balanced with the `OleInitialize` call in `new()`.
        // Must run on the same thread that called `OleInitialize`.
        unsafe {
            OleUninitialize();
        }
    }
}

/// RAII wrapper for a PIDL allocated with `CoTaskMemAlloc`.
///
/// Automatically calls `CoTaskMemFree` on drop.
pub(crate) struct Pidl {
    ptr: *mut ITEMIDLIST,
}

impl Pidl {
    /// Take ownership of a raw PIDL pointer.
    ///
    /// # Safety
    ///
    /// - `ptr` must have been allocated via `CoTaskMemAlloc` (e.g. returned by
    ///   `SHParseDisplayName`), or be null.
    /// - The caller must not free `ptr` after calling this function; `Pidl`
    ///   takes ownership and will free it on drop.
    pub unsafe fn from_raw(ptr: *mut ITEMIDLIST) -> Self {
        Self { ptr }
    }

    /// Get the raw pointer without transferring ownership.
    pub fn as_ptr(&self) -> *const ITEMIDLIST {
        self.ptr as *const ITEMIDLIST
    }
}

impl Drop for Pidl {
    fn drop(&mut self) {
        if !self.ptr.is_null() {
            // SAFETY: `self.ptr` was allocated by `CoTaskMemAlloc` (guaranteed
            // by the safety contract of `from_raw`). Freeing it once here is
            // correct; the null-check prevents double-free.
            unsafe {
                CoTaskMemFree(Some(self.ptr as *const _));
            }
        }
    }
}

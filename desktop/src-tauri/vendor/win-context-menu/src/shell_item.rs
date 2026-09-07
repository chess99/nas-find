//! Resolve filesystem paths into shell objects (`IShellFolder` + child PIDLs).
//!
//! The Windows Shell identifies items not by path strings but by *PIDLs*
//! (Pointer to an Item ID List). This module converts user-supplied paths into
//! the parent `IShellFolder` plus one or more relative child PIDLs that the
//! context-menu APIs require.

use std::path::{Path, PathBuf};

use windows::Win32::System::Com::CoTaskMemAlloc;
use windows::Win32::UI::Shell::Common::ITEMIDLIST;
use windows::Win32::UI::Shell::{IShellFolder, SHBindToObject, SHBindToParent, SHParseDisplayName};

use crate::com::Pidl;
use crate::error::{Error, Result};
use crate::util::{path_to_wide, strip_extended_prefix, wide_to_pcwstr};

/// Resolved shell items ready to create a context menu from.
///
/// Holds the parent `IShellFolder` and one or more child PIDLs. For
/// *background* menus (right-clicking empty space inside a folder), `child_pidls`
/// is empty and the parent itself is the folder.
///
/// Create instances via [`ShellItems::from_path`], [`ShellItems::from_paths`],
/// or [`ShellItems::folder_background`].
pub struct ShellItems {
    pub(crate) parent: IShellFolder,
    pub(crate) child_pidls: Vec<Pidl>,
    /// Keep absolute PIDLs alive so child pointers derived from them stay valid.
    pub(crate) _absolute_pidls: Vec<Pidl>,
    pub(crate) is_background: bool,
    pub(crate) results_host: Option<std::rc::Rc<crate::results_folder::ResultsHost>>,
}

impl ShellItems {
    /// Resolve a single file or folder path to shell items.
    ///
    /// # Errors
    ///
    /// Returns [`Error::ParsePath`] if the path does not exist or cannot be
    /// resolved by the shell.
    pub fn from_path(path: impl AsRef<Path>) -> Result<Self> {
        Self::from_paths(&[path.as_ref().to_path_buf()])
    }

    /// Resolve multiple paths, using a native results folder for cross-directory selections.
    ///
    /// This is the multi-select equivalent — the resulting context menu will
    /// act on all specified items at once (like selecting several files in
    /// Explorer, then right-clicking).
    ///
    /// # Errors
    ///
    /// - [`Error::NoCommonParent`] if `paths` is empty.
    /// - [`Error::ParsePath`] if any path cannot be resolved.
    pub fn from_paths(paths: &[impl AsRef<Path>]) -> Result<Self> {
        if paths.is_empty() {
            return Err(Error::NoCommonParent);
        }

        let mut absolute_pidls = Vec::with_capacity(paths.len());
        let mut child_pidls = Vec::with_capacity(paths.len());
        let mut parent_folder: Option<IShellFolder> = None;
        let mut parent_path: Option<PathBuf> = None;

        for path in paths {
            let path = path.as_ref();
            let canonical = std::fs::canonicalize(path)
                .map(|p| strip_extended_prefix(&p))
                .unwrap_or_else(|_| path.to_path_buf());

            // Keep the direct folder path when possible; otherwise use a results folder.
            let this_parent = canonical.parent().map(|p| p.to_path_buf());
            match (&parent_path, &this_parent) {
                (Some(existing), Some(new)) if existing != new => {
                    return crate::results_folder::from_paths(paths);
                }
                (None, Some(_)) => {
                    parent_path = this_parent;
                }
                _ => {}
            }

            let wide = path_to_wide(&canonical);
            let pcwstr = wide_to_pcwstr(&wide);

            // Parse the display name to an absolute PIDL.
            let mut abs_pidl: *mut ITEMIDLIST = std::ptr::null_mut();
            // SAFETY: `SHParseDisplayName` is a standard Shell API. We pass a
            // valid null-terminated wide string (`pcwstr` derived from `wide`)
            // and receive an output PIDL allocated via `CoTaskMemAlloc`. On
            // success the PIDL is wrapped in `Pidl` for automatic cleanup.
            unsafe {
                SHParseDisplayName(pcwstr, None, &mut abs_pidl, 0, None).map_err(|e| {
                    Error::ParsePath {
                        path: canonical.clone(),
                        source: e,
                    }
                })?;
            }
            // SAFETY: `abs_pidl` was just allocated by `SHParseDisplayName`
            // via `CoTaskMemAlloc`. Ownership transfers to `Pidl`.
            let abs_pidl = unsafe { Pidl::from_raw(abs_pidl) };

            // Bind to parent to get the IShellFolder and a relative child PIDL.
            let mut child_pidl_ptr: *mut ITEMIDLIST = std::ptr::null_mut();
            // SAFETY: `SHBindToParent` takes an absolute PIDL and returns the
            // parent `IShellFolder` plus a pointer to the last (relative) PIDL
            // component. The relative PIDL points *into* `abs_pidl`'s memory,
            // so we copy it into a fresh CoTaskMemAlloc buffer below.
            // `CoTaskMemAlloc` + `copy_nonoverlapping` + null-terminator write
            // are safe because we allocate `child_size + 2` bytes and copy
            // exactly `child_size` bytes.
            let shell_folder: IShellFolder = unsafe {
                let mut child_pidl_raw: *mut ITEMIDLIST = std::ptr::null_mut();
                let folder: IShellFolder =
                    SHBindToParent(abs_pidl.as_ptr(), Some(&mut child_pidl_raw))
                        .map_err(Error::BindToParent)?;

                // Copy the relative child PIDL into its own CoTaskMem buffer
                // because `child_pidl_raw` borrows from `abs_pidl`.
                let child_size = pidl_size(child_pidl_raw as *const _);
                if child_size > 0 {
                    // SAFETY: `CoTaskMemAlloc` returns a block of at least
                    // `child_size + 2` bytes. We copy exactly `child_size`
                    // bytes of PIDL data and write a 2-byte null terminator
                    // (the standard PIDL terminator: a SHITEMID with cb == 0).
                    let alloc = CoTaskMemAlloc(child_size + 2);
                    if !alloc.is_null() {
                        std::ptr::copy_nonoverlapping(
                            child_pidl_raw as *const u8,
                            alloc as *mut u8,
                            child_size,
                        );
                        // Write null terminator (cb = 0)
                        std::ptr::write_bytes((alloc as *mut u8).add(child_size), 0, 2);
                        child_pidl_ptr = alloc as *mut ITEMIDLIST;
                    }
                }

                folder
            };

            if parent_folder.is_none() {
                parent_folder = Some(shell_folder);
            }

            // SAFETY: `child_pidl_ptr` was allocated by `CoTaskMemAlloc` above.
            child_pidls.push(unsafe { Pidl::from_raw(child_pidl_ptr) });
            absolute_pidls.push(abs_pidl);
        }

        Ok(Self {
            parent: parent_folder.unwrap(),
            child_pidls,
            _absolute_pidls: absolute_pidls,
            is_background: false,
            results_host: None,
        })
    }

    /// Create shell items for the **background** of a folder.
    ///
    /// This is equivalent to right-clicking the empty space inside a folder
    /// in Explorer (rather than right-clicking a specific file). The resulting
    /// context menu typically offers "New", "Paste", "View", etc.
    ///
    /// # Errors
    ///
    /// Returns [`Error::ParsePath`] if the folder path cannot be resolved.
    pub fn folder_background(folder: impl AsRef<Path>) -> Result<Self> {
        let folder = folder.as_ref();
        let canonical = std::fs::canonicalize(folder)
            .map(|p| strip_extended_prefix(&p))
            .unwrap_or_else(|_| folder.to_path_buf());

        let wide = path_to_wide(&canonical);
        let pcwstr = wide_to_pcwstr(&wide);

        let mut abs_pidl: *mut ITEMIDLIST = std::ptr::null_mut();
        // SAFETY: Same as the `SHParseDisplayName` call in `from_paths`.
        unsafe {
            SHParseDisplayName(pcwstr, None, &mut abs_pidl, 0, None).map_err(|e| {
                Error::ParsePath {
                    path: canonical.clone(),
                    source: e,
                }
            })?;
        }
        // SAFETY: `abs_pidl` was just allocated by `SHParseDisplayName`.
        let abs_pidl = unsafe { Pidl::from_raw(abs_pidl) };

        // Bind directly to the folder as an IShellFolder so we can ask for its
        // background context menu via `CreateViewObject`.
        // SAFETY: `SHBindToObject` with `None` parent and a valid absolute
        // PIDL returns the corresponding `IShellFolder`.
        let folder_shell: IShellFolder = unsafe {
            SHBindToObject(None, abs_pidl.as_ptr(), None).map_err(Error::BindToParent)?
        };

        Ok(Self {
            parent: folder_shell,
            child_pidls: Vec::new(),
            _absolute_pidls: vec![abs_pidl],
            is_background: true,
            results_host: None,
        })
    }
}

/// Calculate the total byte size of a PIDL chain (excluding the 2-byte null
/// terminator).
///
/// A PIDL is a contiguous sequence of `SHITEMID` structures. Each starts with
/// a `u16 cb` field giving its total size in bytes (including the `cb` field
/// itself). A `cb` value of 0 marks the end.
///
/// # Safety
///
/// `pidl` must point to a valid, null-terminated PIDL or be null.
unsafe fn pidl_size(pidl: *const ITEMIDLIST) -> usize {
    if pidl.is_null() {
        return 0;
    }
    let mut size = 0usize;
    let mut ptr = pidl as *const u8;
    loop {
        // SAFETY: The PIDL layout guarantees a `u16` at every SHITEMID
        // boundary. We use `read_unaligned` because the shell may hand us
        // PIDLs at odd alignments after CoTaskMemAlloc.
        let cb = unsafe { (ptr as *const u16).read_unaligned() };
        if cb == 0 {
            break;
        }
        size += cb as usize;
        // SAFETY: Advancing by `cb` bytes moves to the next SHITEMID in
        // the chain. The chain is terminated by cb == 0.
        ptr = unsafe { ptr.add(cb as usize) };
    }
    size
}

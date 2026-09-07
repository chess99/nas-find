//! Resolve filesystem paths into shell objects (`IShellFolder` + child PIDLs).
//!
//! The Windows Shell identifies items not by path strings but by *PIDLs*
//! (Pointer to an Item ID List). This module converts user-supplied paths into
//! the parent `IShellFolder` plus one or more relative child PIDLs that the
//! context-menu APIs require.

use std::path::Path;

use windows::Win32::Foundation::{E_OUTOFMEMORY, HWND};
use windows::Win32::UI::Shell::{ILCombine, IShellFolder, SHBindToObject, SHParseDisplayName};

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
    pub(crate) file_drop: Option<std::rc::Rc<crate::selection_data::FileDropData>>,
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

        let paths: Vec<_> = paths
            .iter()
            .map(|p| crate::path_parser::absolute(p.as_ref()))
            .collect::<Result<_>>()?;
        if paths.windows(2).any(|p| p[0].parent() != p[1].parent()) {
            return crate::results_folder::from_paths(&paths);
        }
        let mut parser = crate::path_parser::PathParser::default();
        let mut absolute_pidls = Vec::with_capacity(paths.len());
        let mut child_pidls = Vec::with_capacity(paths.len());
        let mut parent_folder = None;
        for path in &paths {
            let (absolute, parent, child) = parser.resolve(path)?;
            if parent_folder.is_none() {
                parent_folder = Some(parent);
            }
            absolute_pidls.push(absolute);
            child_pidls.push(child);
        }
        Ok(Self {
            parent: parent_folder.unwrap(),
            child_pidls,
            _absolute_pidls: absolute_pidls,
            is_background: false,
            results_host: None,
            file_drop: crate::selection_data::FileDropData::for_paths(&paths),
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

        let abs_pidl = parse_absolute_path(&canonical)?;

        // Bind directly to the folder as an IShellFolder so we can ask for its
        // background context menu via `CreateViewObject`.
        // SAFETY: `SHBindToObject` with `None` parent and a valid absolute
        // PIDL returns the corresponding `IShellFolder`.
        let folder_shell: IShellFolder =
            unsafe { SHBindToObject(None, abs_pidl.as_ptr(), None).map_err(Error::BindToParent)? };

        Ok(Self {
            parent: folder_shell,
            child_pidls: Vec::new(),
            _absolute_pidls: vec![abs_pidl],
            is_background: true,
            file_drop: None,
            results_host: None,
        })
    }
}

/// Parse long filesystem paths relative to an existing Shell folder when the
/// desktop parser hits MAX_PATH. An extended-path prefix alone is not accepted
/// by SHParseDisplayName. Keep real Shell PIDLs instead of inventing file data.
pub(crate) fn parse_absolute_path(path: &Path) -> Result<Pidl> {
    fn direct(path: &Path) -> windows::core::Result<Pidl> {
        let wide = path_to_wide(path);
        let mut raw = std::ptr::null_mut();
        // SAFETY: The string is terminated and the output is owned by Pidl,
        // including any allocation returned on a failed call.
        unsafe {
            let result = SHParseDisplayName(wide_to_pcwstr(&wide), None, &mut raw, 0, None);
            let pidl = Pidl::from_raw(raw);
            result?;
            if raw.is_null() {
                return Err(windows::core::Error::from_hresult(E_OUTOFMEMORY));
            }
            Ok(pidl)
        }
    }

    let resolve = || -> windows::core::Result<Pidl> {
        let first_error = match direct(path) {
            Ok(pidl) => return Ok(pidl),
            Err(error) => error,
        };
        if path_to_wide(path).len() <= 260 {
            return Err(first_error);
        }

        // Find a parsable ancestor iteratively, then descend one name at a
        // time. This also covers parent directories longer than MAX_PATH.
        let mut ancestor = path;
        let mut names = Vec::new();
        let mut absolute = loop {
            let Some(name) = ancestor.file_name() else {
                return Err(first_error);
            };
            let Some(parent) = ancestor.parent() else {
                return Err(first_error);
            };
            names.push(name);
            ancestor = parent;
            if let Ok(pidl) = direct(ancestor) {
                break pidl;
            }
        };
        for name in names.into_iter().rev() {
            let wide = path_to_wide(Path::new(name));
            let mut raw = std::ptr::null_mut();
            // SAFETY: Bind uses a live absolute PIDL. ParseDisplayName returns
            // an owned child PIDL; ILCombine copies both into a new allocation.
            unsafe {
                let folder: IShellFolder = SHBindToObject(None, absolute.as_ptr(), None)?;
                let result = folder.ParseDisplayName(
                    HWND::default(),
                    None,
                    wide_to_pcwstr(&wide),
                    None,
                    &mut raw,
                    std::ptr::null_mut(),
                );
                let child = Pidl::from_raw(raw);
                result?;
                if raw.is_null() {
                    return Err(windows::core::Error::from_hresult(E_OUTOFMEMORY));
                }
                let combined = ILCombine(Some(absolute.as_ptr()), Some(child.as_ptr()));
                if combined.is_null() {
                    return Err(windows::core::Error::from_hresult(E_OUTOFMEMORY));
                }
                absolute = Pidl::from_raw(combined);
            }
        }
        Ok(absolute)
    };
    resolve().map_err(|source| Error::ParsePath {
        path: path.to_path_buf(),
        source,
    })
}

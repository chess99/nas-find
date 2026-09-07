//! Resolve each directory once per selection instead of walking it for every file.
use crate::{
    com::Pidl,
    error::{Error, Result},
    shell_item::parse_absolute_path,
    util::{path_to_wide, strip_extended_prefix, wide_to_pcwstr},
};
use std::{
    collections::HashMap,
    path::{Path, PathBuf},
};
use windows::Win32::{
    Foundation::{E_OUTOFMEMORY, HWND},
    UI::Shell::*,
};

pub(crate) fn absolute(path: &Path) -> Result<PathBuf> {
    // Pure path normalization: no file opens and no SMB canonicalization requests.
    std::path::absolute(strip_extended_prefix(path)).map_err(|e| Error::ParsePath {
        path: path.to_path_buf(),
        source: windows::core::Error::from_hresult(windows::core::HRESULT::from_win32(
            e.raw_os_error().unwrap_or(87) as u32,
        )),
    })
}

#[derive(Default)]
pub(crate) struct PathParser {
    parents: HashMap<PathBuf, (Pidl, IShellFolder)>,
}

impl PathParser {
    fn folder(&mut self, path: &Path) -> Result<()> {
        let mut missing = Vec::new();
        let mut cursor = path;
        while !self.parents.contains_key(cursor) {
            missing.push(cursor.to_path_buf());
            let (Some(parent), Some(_)) = (cursor.parent(), cursor.file_name()) else {
                break;
            };
            cursor = parent;
        }
        for directory in missing.into_iter().rev() {
            let resolved = directory
                .parent()
                .zip(directory.file_name())
                .and_then(|(parent, name)| self.parents.get(parent).map(|data| (data, name)));
            let (id, folder) = if let Some(((parent_id, parent), name)) = resolved {
                let wide = path_to_wide(Path::new(name));
                let mut raw = std::ptr::null_mut();
                // SAFETY: parse and bind only one child of the cached live parent.
                unsafe {
                    let result = parent.ParseDisplayName(
                        HWND::default(),
                        None,
                        wide_to_pcwstr(&wide),
                        None,
                        &mut raw,
                        std::ptr::null_mut(),
                    );
                    let child = Pidl::from_raw(raw);
                    result.map_err(|source| Error::ParsePath {
                        path: directory.clone(),
                        source,
                    })?;
                    if raw.is_null() {
                        return Err(Error::Windows(windows::core::Error::from_hresult(
                            E_OUTOFMEMORY,
                        )));
                    }
                    let folder: IShellFolder = parent.BindToObject(child.as_ptr(), None)?;
                    let combined = ILCombine(Some(parent_id.as_ptr()), Some(child.as_ptr()));
                    if combined.is_null() {
                        return Err(Error::Windows(windows::core::Error::from_hresult(
                            E_OUTOFMEMORY,
                        )));
                    }
                    (Pidl::from_raw(combined), folder)
                }
            } else {
                let id = parse_absolute_path(&directory)?;
                // SAFETY: root id is live; returned folder is an owned COM reference.
                let folder = unsafe { SHBindToObject(None, id.as_ptr(), None)? };
                (id, folder)
            };
            self.parents.insert(directory, (id, folder));
        }
        Ok(())
    }

    pub(crate) fn resolve(&mut self, path: &Path) -> Result<(Pidl, IShellFolder, Pidl)> {
        let resolve = || -> Result<(Pidl, IShellFolder, Pidl)> {
            let absolute = parse_absolute_path(path)?;
            // Root objects have no filename to parse relative to their parent.
            unsafe {
                let mut child = std::ptr::null_mut();
                let folder: IShellFolder = SHBindToParent(absolute.as_ptr(), Some(&mut child))?;
                let copy = ILClone(child);
                if copy.is_null() {
                    return Err(Error::Windows(windows::core::Error::from_hresult(
                        E_OUTOFMEMORY,
                    )));
                }
                Ok((absolute, folder, Pidl::from_raw(copy)))
            }
        };
        let (Some(parent), Some(name)) = (path.parent(), path.file_name()) else {
            return resolve();
        };
        self.folder(parent)?;
        let (parent_id, folder) = self.parents.get(parent).unwrap();
        let wide = path_to_wide(Path::new(name));
        let mut raw = std::ptr::null_mut();
        // SAFETY: the terminated filename and parent COM object are valid. Both
        // output PIDLs are owned allocations; ILCombine copies its input PIDLs.
        unsafe {
            let result = folder.ParseDisplayName(
                HWND::default(),
                None,
                wide_to_pcwstr(&wide),
                None,
                &mut raw,
                std::ptr::null_mut(),
            );
            let child = Pidl::from_raw(raw);
            result.map_err(|source| Error::ParsePath {
                path: path.to_path_buf(),
                source,
            })?;
            if raw.is_null() {
                return Err(Error::Windows(windows::core::Error::from_hresult(
                    E_OUTOFMEMORY,
                )));
            }
            let combined = ILCombine(Some(parent_id.as_ptr()), Some(child.as_ptr()));
            if combined.is_null() {
                return Err(Error::Windows(windows::core::Error::from_hresult(
                    E_OUTOFMEMORY,
                )));
            }
            Ok((Pidl::from_raw(combined), folder.clone(), child))
        }
    }
}

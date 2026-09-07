//! Supply the full file-drop list when Windows' legacy renderer cannot handle long paths.
use crate::{
    com::Pidl,
    error::{Error, Result},
    util::path_to_wide,
};
use std::{
    cell::RefCell,
    mem::{size_of, ManuallyDrop},
    path::PathBuf,
    rc::Rc,
};
use windows::{
    core::{implement, Error as WinError, Result as WinResult},
    Win32::{
        Foundation::{E_FAIL, E_NOTIMPL, E_OUTOFMEMORY, HWND, LPARAM, S_FALSE, TRUE, WPARAM},
        System::{Com::*, Memory::*, Ole::ReleaseStgMedium},
        UI::Shell::*,
    },
};

pub(crate) struct FileDropData {
    bytes: Vec<u8>,
    status: RefCell<Option<WinResult<()>>>,
}
impl FileDropData {
    pub(crate) fn for_paths(paths: &[PathBuf]) -> Option<Rc<Self>> {
        if !paths.iter().any(|p| path_to_wide(p).len() > 260) {
            return None;
        }
        let mut bytes = vec![0; size_of::<DROPFILES>()];
        for path in paths {
            for unit in path_to_wide(path) {
                bytes.extend_from_slice(&unit.to_le_bytes());
            }
        }
        bytes.extend_from_slice(&[0, 0]); // list terminator, after each path's terminator
        Some(Rc::new(Self {
            bytes,
            status: RefCell::new(None),
        }))
    }

    pub(crate) fn install(&self, object: &IDataObject) -> WinResult<()> {
        // SAFETY: allocate exactly the header plus terminated UTF-16 list. The
        // data object takes ownership only on successful SetData(fRelease=true).
        unsafe {
            let memory = GlobalAlloc(GMEM_MOVEABLE | GMEM_ZEROINIT, self.bytes.len())?;
            let target = GlobalLock(memory) as *mut u8;
            if target.is_null() {
                let _ = windows::Win32::Foundation::GlobalFree(memory);
                return Err(WinError::from_hresult(E_OUTOFMEMORY));
            }
            std::ptr::copy_nonoverlapping(self.bytes.as_ptr(), target, self.bytes.len());
            (target as *mut DROPFILES).write(DROPFILES {
                pFiles: size_of::<DROPFILES>() as u32,
                fWide: TRUE,
                ..Default::default()
            });
            let _ = GlobalUnlock(memory);
            let mut medium = STGMEDIUM {
                tymed: 1,
                u: STGMEDIUM_0 { hGlobal: memory },
                ..Default::default()
            };
            let result = object.SetData(
                &FORMATETC {
                    cfFormat: 15,
                    dwAspect: 1,
                    lindex: -1,
                    tymed: 1,
                    ..Default::default()
                },
                &medium,
                true,
            );
            if result.is_err() {
                ReleaseStgMedium(&mut medium);
            }
            result
        }
    }

    pub(crate) fn check(&self) -> Result<()> {
        self.status
            .borrow()
            .clone()
            .unwrap_or_else(|| Err(WinError::from_hresult(E_FAIL)))
            .map_err(Error::Windows)
    }

    pub(crate) fn context_menu(
        self: &Rc<Self>,
        parent: &IShellFolder,
        children: &[*const Common::ITEMIDLIST],
        hwnd: HWND,
    ) -> Result<IContextMenu> {
        *self.status.borrow_mut() = None;
        let callback: IContextMenuCB = SelectionCallback(self.clone()).into();
        // SAFETY: native folder and child PIDLs remain owned by ShellItems.
        // The constructor retains its COM inputs; release the temporary copies.
        unsafe {
            let parent_id = Pidl::from_raw(SHGetIDListFromObject(parent)?);
            let mut child_ids: Vec<_> = children.iter().map(|p| *p as *mut _).collect();
            let mut definition = DEFCONTEXTMENU {
                hwnd,
                pidlFolder: parent_id.as_ptr() as *mut _,
                psf: ManuallyDrop::new(Some(parent.clone())),
                pcmcb: ManuallyDrop::new(Some(callback)),
                cidl: child_ids.len() as u32,
                apidl: child_ids.as_mut_ptr(),
                ..Default::default()
            };
            let result = SHCreateDefaultContextMenu(&definition);
            ManuallyDrop::drop(&mut definition.psf);
            ManuallyDrop::drop(&mut definition.pcmcb);
            result.map_err(Error::GetContextMenu)
        }
    }
}

#[implement(IContextMenuCB)]
struct SelectionCallback(Rc<FileDropData>);
impl IContextMenuCB_Impl for SelectionCallback_Impl {
    fn CallBack(
        &self,
        _folder: Option<&IShellFolder>,
        _owner: HWND,
        object: Option<&IDataObject>,
        message: u32,
        _wparam: WPARAM,
        _lparam: LPARAM,
    ) -> WinResult<()> {
        if message == DFM_MERGECONTEXTMENU.0 as u32 {
            let result = object
                .ok_or_else(|| WinError::from_hresult(E_FAIL))
                .and_then(|o| self.0.install(o));
            *self.0.status.borrow_mut() = Some(result.clone());
            result
        } else if [DFM_INVOKECOMMAND, DFM_INVOKECOMMANDEX, DFM_GETDEFSTATICID]
            .iter()
            .any(|m| m.0 as u32 == message)
        {
            // S_FALSE asks the Shell to perform its standard command handling.
            Err(WinError::from_hresult(S_FALSE))
        } else {
            Err(WinError::from_hresult(E_NOTIMPL))
        }
    }
}

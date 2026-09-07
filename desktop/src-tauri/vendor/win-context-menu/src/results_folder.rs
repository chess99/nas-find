//! Native cross-directory selection, using the documented ExplorerBrowser results folder.
use crate::{
    com::Pidl,
    error::{Error, Result},
    hidden_window::HiddenWindow,
    shell_item::{parse_absolute_path, ShellItems},
    util::strip_extended_prefix,
};
use std::{
    path::Path,
    rc::Rc,
    time::{Duration, Instant},
};
use windows::Win32::{
    Foundation::{E_ABORT, E_FAIL, RECT},
    System::Com::{CoCreateInstance, CLSCTX_INPROC_SERVER},
    UI::{Shell::*, WindowsAndMessaging::*},
};

/// Keep the results folder's native host alive until command invocation completes.
pub(crate) struct ResultsHost {
    browser: IExplorerBrowser,
    _window: HiddenWindow,
}
impl Drop for ResultsHost {
    fn drop(&mut self) {
        // All construction, use and destruction happen on the same STA thread.
        unsafe {
            let _ = self.browser.Destroy();
        }
    }
}

pub(crate) fn from_paths(paths: &[impl AsRef<Path>]) -> Result<ShellItems> {
    let mut absolute = Vec::with_capacity(paths.len());
    for path in paths {
        let path = path.as_ref();
        let canonical = std::fs::canonicalize(path)
            .map(|p| strip_extended_prefix(&p))
            .unwrap_or_else(|_| path.to_path_buf());
        absolute.push(parse_absolute_path(&canonical)?);
    }
    let references: Vec<_> = absolute.iter().map(Pidl::as_ptr).collect();
    let window = HiddenWindow::new()?;
    // This is an invisible native Shell control, not a new application or a web browser.
    unsafe {
        let browser: IExplorerBrowser =
            CoCreateInstance(&ExplorerBrowser, None, CLSCTX_INPROC_SERVER)?;
        let host = Rc::new(ResultsHost {
            browser,
            _window: window,
        });
        host.browser.SetOptions(
            EBO_NOPERSISTVIEWSTATE | EBO_NOTRAVELLOG | EBO_NOBORDER | EBO_NAVIGATEONCE,
        )?;
        let settings = FOLDERSETTINGS {
            ViewMode: FVM_DETAILS.0 as u32,
            fFlags: (FWF_NOICONS | FWF_NOGROUPING | FWF_NOBROWSERVIEWSTATE).0 as u32,
        };
        host.browser.Initialize(
            host._window.hwnd,
            &RECT {
                left: 0,
                top: 0,
                right: 1,
                bottom: 1,
            },
            Some(&settings),
        )?;
        // Create an empty native results folder, then add exactly the selected objects.
        // Filling from an item array can walk selected folders and substitute children.
        host.browser.FillFromObject(None, EBF_NODROPTARGET)?;
        let started = Instant::now();
        let view: IFolderView = loop {
            if let Ok(view) = host.browser.GetCurrentView::<IFolderView>() {
                break view;
            }
            if started.elapsed() > Duration::from_secs(120) {
                return Err(Error::IncompleteSelection);
            }
            // Native folder population is asynchronous. Pump this STA's messages,
            // preserving WM_QUIT so closing the app still works during preparation.
            let mut message = MSG::default();
            while PeekMessageW(&mut message, None, 0, 0, PM_REMOVE).as_bool() {
                if message.message == WM_QUIT {
                    PostQuitMessage(message.wParam.0 as i32);
                    return Err(Error::Windows(windows::core::Error::from_hresult(E_ABORT)));
                }
                let _ = TranslateMessage(&message);
                DispatchMessageW(&message);
            }
            std::thread::sleep(Duration::from_millis(10));
        };
        let parent: IShellFolder = view.GetFolder()?;
        let folder: IResultsFolder = windows::core::Interface::cast(&parent)?;
        let mut children = Vec::with_capacity(paths.len());
        for absolute in &references {
            let mut child = std::ptr::null_mut();
            folder.AddIDList(*absolute, Some(&mut child))?;
            if child.is_null() {
                return Err(Error::Windows(windows::core::Error::from_hresult(E_FAIL)));
            }
            children.push(Pidl::from_raw(child));
        }
        Ok(ShellItems {
            parent,
            child_pidls: children,
            _absolute_pidls: absolute,
            is_background: false,
            results_host: Some(host),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{init_com, ContextMenu};
    use windows::Win32::{
        Foundation::HWND,
        System::{
            Com::{IDataObject, FORMATETC},
            Ole::ReleaseStgMedium,
        },
    };

    fn verify(paths: &[std::path::PathBuf]) {
        let items = ShellItems::from_paths(paths).unwrap();
        if paths.windows(2).any(|p| p[0].parent() != p[1].parent()) {
            assert!(
                items.results_host.is_some(),
                "expected a cross-directory selection"
            );
        }
        let ids: Vec<_> = items.child_pidls.iter().map(Pidl::as_ptr).collect();
        // Inspect the data object that extensions will receive, without invoking file actions.
        unsafe {
            let object: IDataObject = items
                .parent
                .GetUIObjectOf(HWND::default(), &ids, None)
                .unwrap();
            let array: IShellItemArray = SHCreateShellItemArrayFromDataObject(&object).unwrap();
            let mut item_paths = Vec::new();
            for i in 0..array.GetCount().unwrap() {
                let name = array
                    .GetItemAt(i)
                    .unwrap()
                    .GetDisplayName(SIGDN_FILESYSPATH)
                    .unwrap();
                let value = name.to_string().unwrap();
                windows::Win32::System::Com::CoTaskMemFree(Some(name.0 as *const _));
                item_paths.push(strip_extended_prefix(Path::new(&value)));
            }
            let normalize = |path: &std::path::PathBuf| {
                strip_extended_prefix(&std::fs::canonicalize(path).unwrap())
            };
            let mut expected: Vec<_> = paths.iter().map(normalize).collect();
            expected.sort();
            item_paths = item_paths.iter().map(normalize).collect();
            item_paths.sort();
            assert!(
                item_paths == expected,
                "Shell item array must preserve all {} selected paths",
                paths.len()
            );
            let medium = object.GetData(&FORMATETC {
                cfFormat: 15,
                dwAspect: 1,
                lindex: -1,
                tymed: 1,
                ..Default::default()
            });
            if let Ok(mut medium) = medium {
                let files = HDROP(medium.u.hGlobal.0);
                let count = DragQueryFileW(files, u32::MAX, None);
                let mut actual = Vec::new();
                for i in 0..count {
                    let mut buffer = vec![0u16; 32768];
                    let length = DragQueryFileW(files, i, Some(&mut buffer));
                    actual.push(strip_extended_prefix(Path::new(&String::from_utf16_lossy(
                        &buffer[..length as usize],
                    ))));
                }
                ReleaseStgMedium(&mut medium);
                // Windows can return an 8.3 alias or extended UNC path for long names.
                // Resolve both sides before comparing the exact set of selected files.
                actual = actual.iter().map(normalize).collect();
                actual.sort();
                assert!(actual == expected,"the Shell must receive every selected path exactly once ({} actual, {} expected)", actual.len(), expected.len());
            } else {
                // Windows' legacy CF_HDROP renderer can reject long NAS paths
                // without 8.3 aliases. The Shell item array above is complete;
                // this OS/extension limitation must not be mistaken for lost items.
                let error = medium.err().unwrap();
                assert_eq!(error.code().0 as u32, 0x8007007A);
                assert!(paths
                    .iter()
                    .any(|p| p.to_string_lossy().encode_utf16().count() >= 260));
                println!("Windows legacy CF_HDROP cannot render this long-path selection; Shell item array is complete");
            }
        }
        let menu = ContextMenu::new(items).unwrap();
        let labels = menu.enumerate().unwrap();
        assert!(labels
            .iter()
            .any(|item| item.command_string.as_deref() == Some("copy")));
        println!(
            "Verified {} selected paths; {} Shell menu entries",
            paths.len(),
            labels.len()
        );
    }

    #[test]
    fn cross_directory_data_objects_preserve_files_types_and_folders() {
        let _com = init_com().unwrap();
        let temp = tempfile::tempdir().unwrap();
        let left = temp.path().join("left");
        let right = temp.path().join("right");
        std::fs::create_dir(&left).unwrap();
        std::fs::create_dir(&right).unwrap();
        let first = left.join("同名 视频.mp4");
        let second = right.join("同名 视频.mp4");
        let text = right.join("notes.txt");
        for path in [&first, &second, &text] {
            std::fs::write(path, b"fixture").unwrap();
        }
        let folder = right.join("folder");
        std::fs::create_dir(&folder).unwrap();
        std::fs::write(folder.join("not-selected.txt"), b"do not enumerate").unwrap();
        verify(&[first.clone(), second]);
        verify(&[first.clone(), text]);
        verify(&[first, folder]);
        verify(&[left, right]);
        assert!(ShellItems::from_paths(&[
            temp.path().join("missing/a.txt"),
            temp.path().join("missing/b.mp4")
        ])
        .is_err());
    }

    #[test]
    fn long_paths_preserve_complete_shell_selection() {
        let _com = init_com().unwrap();
        let temp = tempfile::tempdir().unwrap();
        let parent = temp
            .path()
            .join("nested-".repeat(12))
            .join("deeper-".repeat(12));
        let deep = parent.join("directory-".repeat(10));
        std::fs::create_dir_all(&deep).unwrap();
        let long = parent.join(format!("{} 视频.mp4", "long-name-".repeat(15)));
        let short = temp.path().join("short.mp4");
        let nested = deep.join("nested.mp4");
        for path in [&long, &short, &nested] {
            std::fs::write(path, b"fixture").unwrap();
        }
        assert!(long.to_string_lossy().encode_utf16().count() > 260);
        assert!(deep.to_string_lossy().encode_utf16().count() > 260);
        verify(&[long.clone()]);
        verify(&[long.clone(), short.clone(), nested]);
        verify(&[deep.clone(), short.clone()]);
        assert!(ShellItems::folder_background(&deep).is_ok());
        let missing = parent.join(format!("{}.mp4", "missing".repeat(24)));
        assert!(ShellItems::from_paths(&[short, missing]).is_err());
    }

    #[test]
    #[ignore = "requires NAS_MENU_TEST_PATHS_FILE; reads selected files and enumerates menu, never invokes commands"]
    fn configured_selection_is_complete() {
        let _com = init_com().unwrap();
        let path = std::env::var_os("NAS_MENU_TEST_PATHS_FILE").unwrap();
        let paths: Vec<_> = std::fs::read_to_string(path)
            .unwrap()
            .lines()
            .map(std::path::PathBuf::from)
            .collect();
        assert!(!paths.is_empty());
        verify(&paths);
    }
}

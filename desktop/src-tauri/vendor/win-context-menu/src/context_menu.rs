//! Core context-menu builder: query, display, and enumerate shell menus.
//!
//! The [`ContextMenu`] builder wraps the Win32 `IContextMenu` / `HMENU`
//! lifecycle: obtain the interface from `IShellFolder`, call
//! `QueryContextMenu` to populate an `HMENU`, optionally show it with
//! `TrackPopupMenu`, and finally invoke or inspect the result.

use windows::Win32::Foundation::HWND;
use windows::Win32::System::Com::FORMATETC;
use windows::Win32::System::Ole::OleGetClipboard;
use windows::Win32::UI::Shell::Common::ITEMIDLIST;
use windows::Win32::UI::Shell::{
    CMF_EXPLORE, CMF_EXTENDEDVERBS, CMF_NORMAL, GCS_VERBA, IContextMenu, IContextMenu2,
    IContextMenu3,
};
use windows::Win32::UI::WindowsAndMessaging::*;
use windows::core::{Interface, PSTR};

use crate::error::{Error, Result};
use crate::hidden_window::HiddenWindow;
use crate::invoke::invoke_command;
use crate::menu_items::{InvokeParams, MenuItem, SelectedItem};
use crate::shell_item::ShellItems;

/// First command ID passed to `QueryContextMenu`. IDs in the range
/// `[ID_FIRST, ID_LAST]` belong to our menu.
const ID_FIRST: u32 = 1;
/// Last command ID.
const ID_LAST: u32 = 0x7FFF;
/// Sentinel command ID for the injected "Paste" item on background menus.
const ID_PASTE_INJECTED: u32 = 0x8000;

/// CF_HDROP clipboard format ID (file drop format).
const CF_HDROP: u16 = 15;

/// Builder for displaying a Windows Explorer context menu.
///
/// Create one via [`ContextMenu::new`], optionally configure it with
/// [`extended`](ContextMenu::extended) or [`owner`](ContextMenu::owner), then
/// call [`show`](ContextMenu::show) / [`show_at`](ContextMenu::show_at) to
/// display the menu, or [`enumerate`](ContextMenu::enumerate) to list items
/// without showing anything.
///
/// # Example
///
/// ```no_run
/// use win_context_menu::{init_com, ContextMenu, ShellItems};
///
/// let _com = init_com()?;
/// let items = ShellItems::from_path(r"C:\Windows\notepad.exe")?;
/// let selected = ContextMenu::new(items)?.extended(true).show()?;
/// if let Some(sel) = selected {
///     sel.execute()?;
/// }
/// # Ok::<(), win_context_menu::Error>(())
/// ```
pub struct ContextMenu {
    items: ShellItems,
    extended: bool,
    owner_hwnd: Option<isize>,
}

impl ContextMenu {
    /// Create a new context menu builder for the given shell items.
    pub fn new(items: ShellItems) -> Result<Self> {
        Ok(Self {
            items,
            extended: false,
            owner_hwnd: None,
        })
    }

    /// Enable extended verbs (equivalent to Shift+right-click).
    ///
    /// Extended menus expose additional items like "Copy as path" or "Open
    /// PowerShell window here" that are normally hidden.
    pub fn extended(mut self, yes: bool) -> Self {
        self.extended = yes;
        self
    }

    /// Set an explicit owner window handle (as a raw `isize` / `HWND`).
    ///
    /// If not set, a hidden helper window is created automatically. Set this
    /// when embedding the menu in an existing GUI application (e.g., Electron
    /// or a native Win32 app) so the menu is owned by your main window.
    pub fn owner(mut self, hwnd: isize) -> Self {
        self.owner_hwnd = Some(hwnd);
        self
    }

    /// Show the context menu at the specified screen coordinates.
    ///
    /// Returns `Ok(Some(item))` if the user selected an item, or `Ok(None)` if
    /// the menu was dismissed without a selection.
    pub fn show_at(self, x: i32, y: i32) -> Result<Option<SelectedItem>> {
        let hidden_window = HiddenWindow::new()?;
        let hwnd = if let Some(h) = self.owner_hwnd {
            HWND(h as *mut _)
        } else {
            hidden_window.hwnd
        };

        let ctx_menu = self.get_context_menu_with_hwnd(hwnd)?;

        // SAFETY: `CreatePopupMenu` allocates a new empty HMENU. Cannot fail
        // in practice, but we propagate the error anyway.
        let hmenu = unsafe { CreatePopupMenu().map_err(Error::Windows)? };

        let flags = self.query_flags();
        // SAFETY: `ctx_menu` is a valid IContextMenu obtained from the shell.
        // `hmenu` is a valid empty menu handle. `ID_FIRST`..`ID_LAST` defines
        // the range of command IDs the shell may assign.
        unsafe {
            ctx_menu
                .QueryContextMenu(hmenu, 0, ID_FIRST, ID_LAST, flags)
                .map_err(Error::QueryContextMenu)?;
        }

        // For background menus, inject clipboard-related items (Paste) that
        // CreateViewObject doesn't include by default.
        if self.items.is_background {
            inject_clipboard_items(hmenu);
        }

        // Query for IContextMenu2/3 for owner-drawn submenu support.
        let ctx2: Option<IContextMenu2> = ctx_menu.cast().ok();
        let ctx3: Option<IContextMenu3> = ctx_menu.cast().ok();

        hidden_window.set_context_menu_handlers(ctx2, ctx3);

        // SAFETY: `SetForegroundWindow` with our window handle. Required so
        // the menu dismisses when the user clicks outside it.
        unsafe {
            let _ = SetForegroundWindow(hwnd);
        }

        // SAFETY: `TrackPopupMenu` shows a modal popup menu. `TPM_RETURNCMD`
        // means the selected command ID is returned directly instead of being
        // posted as a message. The return value is 0 if nothing was selected.
        let cmd = unsafe {
            TrackPopupMenu(
                hmenu,
                TPM_RETURNCMD | TPM_RIGHTBUTTON,
                x,
                y,
                0,
                hidden_window.hwnd,
                None,
            )
        };

        let selected = if cmd.as_bool() {
            let command_id = cmd.0 as u32;
            if command_id == ID_PASTE_INJECTED {
                // Injected "Paste" item — invoke via verb string
                let ctx_menu_clone = ctx_menu.clone();
                let hwnd_val = hwnd;
                let results_host = self.items.results_host.clone();
                Some(SelectedItem {
                    menu_item: MenuItem {
                        id: ID_PASTE_INJECTED,
                        label: "Paste".to_string(),
                        command_string: Some("paste".to_string()),
                        is_separator: false,
                        is_disabled: false,
                        is_checked: false,
                        is_default: false,
                        submenu: None,
                    },
                    command_id: ID_PASTE_INJECTED,
                    invoker: Some(Box::new(move |_params: Option<InvokeParams>| {
                        let _keep_results_alive = &results_host;
                        crate::invoke::invoke_command_by_verb(&ctx_menu_clone, "paste", hwnd_val)
                    })),
                    _hidden_window: Some(hidden_window),
                })
            } else {
                let item = get_menu_item_info_for_id(&ctx_menu, hmenu, command_id)?;
                let ctx_menu_clone = ctx_menu.clone();
                let hwnd_val = hwnd;
                let results_host = self.items.results_host.clone();
                Some(SelectedItem {
                    menu_item: item,
                    command_id,
                    invoker: Some(Box::new(move |params: Option<InvokeParams>| {
                        let _keep_results_alive = &results_host;
                        invoke_command(&ctx_menu_clone, command_id - ID_FIRST, hwnd_val, params)
                    })),
                    _hidden_window: Some(hidden_window),
                })
            }
        } else {
            None
        };

        // SAFETY: `hmenu` is a valid menu handle we created above.
        unsafe {
            let _ = DestroyMenu(hmenu);
        }

        Ok(selected)
    }

    /// Show the context menu at the current cursor position.
    ///
    /// Convenience wrapper around [`show_at`](ContextMenu::show_at).
    pub fn show(self) -> Result<Option<SelectedItem>> {
        let mut point = windows::Win32::Foundation::POINT::default();
        // SAFETY: `GetCursorPos` writes the current cursor position into the
        // provided POINT struct.
        unsafe {
            let _ = GetCursorPos(&mut point);
        }
        self.show_at(point.x, point.y)
    }

    /// Enumerate all menu items without showing the menu.
    ///
    /// Returns a flat list of [`MenuItem`] structs (submenus are nested inside
    /// the `submenu` field). Useful for building custom UIs or for testing.
    pub fn enumerate(&self) -> Result<Vec<MenuItem>> {
        let hidden_window = HiddenWindow::new()?;
        let ctx_menu = self.get_context_menu_with_hwnd(hidden_window.hwnd)?;

        // SAFETY: `CreatePopupMenu` allocates a new empty HMENU.
        let hmenu = unsafe { CreatePopupMenu().map_err(Error::Windows)? };

        let flags = self.query_flags();
        // SAFETY: Same as in `show_at`.
        // SAFETY: Same as in `show_at`.
        unsafe {
            ctx_menu
                .QueryContextMenu(hmenu, 0, ID_FIRST, ID_LAST, flags)
                .map_err(Error::QueryContextMenu)?;
        }

        // For background menus, inject clipboard-related items (Paste).
        if self.items.is_background {
            inject_clipboard_items(hmenu);
        }

        let items = enumerate_menu(&ctx_menu, hmenu)?;

        // SAFETY: `hmenu` is a valid menu handle we created above.
        unsafe {
            let _ = DestroyMenu(hmenu);
        }

        Ok(items)
    }

    /// Invoke a shell verb directly without showing the menu.
    ///
    /// This is useful for programmatically executing commands like "copy", "cut",
    /// or "paste" in response to keyboard shortcuts.
    ///
    /// # Example
    ///
    /// ```no_run
    /// use win_context_menu::{init_com, ContextMenu, ShellItems};
    ///
    /// let _com = init_com()?;
    /// let items = ShellItems::from_path(r"C:\some\file.txt")?;
    /// ContextMenu::new(items)?.invoke_verb("copy")?;
    /// # Ok::<(), win_context_menu::Error>(())
    /// ```
    pub fn invoke_verb(&self, verb: &str) -> Result<()> {
        let hidden_window = HiddenWindow::new()?;
        let hwnd = if let Some(h) = self.owner_hwnd {
            HWND(h as *mut _)
        } else {
            hidden_window.hwnd
        };

        let ctx_menu = self.get_context_menu_with_hwnd(hwnd)?;

        // QueryContextMenu is required before InvokeCommand — the shell handler
        // needs it to initialise internal state even when we don't show a menu.
        let hmenu = unsafe { CreatePopupMenu().map_err(Error::Windows)? };
        let flags = self.query_flags();
        unsafe {
            ctx_menu
                .QueryContextMenu(hmenu, 0, ID_FIRST, ID_LAST, flags)
                .map_err(Error::QueryContextMenu)?;
        }

        let result = crate::invoke::invoke_command_by_verb(&ctx_menu, verb, hwnd);

        unsafe {
            let _ = DestroyMenu(hmenu);
        }

        result
    }

    fn query_flags(&self) -> u32 {
        let mut flags = CMF_NORMAL;
        if !self.items.is_background {
            flags |= CMF_EXPLORE;
        }
        if self.extended {
            flags |= CMF_EXTENDEDVERBS;
        }
        flags
    }

    fn get_context_menu_with_hwnd(&self, hwnd: HWND) -> Result<IContextMenu> {
        if self.items.is_background {
            // Background context menu — ask the folder's IShellFolder for the
            // background menu via CreateViewObject.
            // SAFETY: `CreateViewObject` is a COM call on our valid IShellFolder.
            unsafe {
                let menu: IContextMenu = self
                    .items
                    .parent
                    .CreateViewObject(hwnd)
                    .map_err(Error::GetContextMenu)?;
                Ok(menu)
            }
        } else {
            // Item context menu — ask the parent folder for a UI object that
            // implements IContextMenu for the given child PIDLs.
            let pidl_ptrs: Vec<*const ITEMIDLIST> =
                self.items.child_pidls.iter().map(|p| p.as_ptr()).collect();

            // SAFETY: `GetUIObjectOf` is a COM call on our valid IShellFolder.
            // `pidl_ptrs` contains valid child-relative PIDLs owned by
            // `self.items.child_pidls`.
            unsafe {
                let menu: IContextMenu = self
                    .items
                    .parent
                    .GetUIObjectOf(hwnd, &pidl_ptrs, None)
                    .map_err(Error::GetContextMenu)?;
                Ok(menu)
            }
        }
    }
}

/// Walk every item in an `HMENU` and build a `Vec<MenuItem>`.
fn enumerate_menu(ctx_menu: &IContextMenu, hmenu: HMENU) -> Result<Vec<MenuItem>> {
    // SAFETY: `GetMenuItemCount` with a valid HMENU.
    let count = unsafe { GetMenuItemCount(hmenu) };
    if count < 0 {
        return Ok(Vec::new());
    }

    let mut items = Vec::new();

    for i in 0..count {
        let mut mii = MENUITEMINFOW {
            cbSize: std::mem::size_of::<MENUITEMINFOW>() as u32,
            fMask: MIIM_ID | MIIM_FTYPE | MIIM_STATE | MIIM_SUBMENU | MIIM_STRING,
            ..Default::default()
        };

        // First call: get the required buffer size for the label string.
        // SAFETY: `GetMenuItemInfoW` with `fByPosition = true` reads info
        // about the i-th item. With `cch = 0` it just returns the string
        // length in `mii.cch`.
        unsafe {
            let _ = GetMenuItemInfoW(hmenu, i as u32, true, &mut mii);
        }

        if mii.fType.contains(MFT_SEPARATOR) {
            items.push(MenuItem::separator());
            continue;
        }

        // Second call: actually read the label text.
        let mut label_buf = vec![0u16; (mii.cch + 1) as usize];
        mii.dwTypeData = windows::core::PWSTR(label_buf.as_mut_ptr());
        mii.cch += 1;
        // SAFETY: `label_buf` is large enough (cch + 1 wide chars).
        unsafe {
            let _ = GetMenuItemInfoW(hmenu, i as u32, true, &mut mii);
        }

        let label = String::from_utf16_lossy(&label_buf[..mii.cch as usize])
            .replace('&', "");

        let id = mii.wID;

        let command_string = if (ID_FIRST..=ID_LAST).contains(&id) {
            get_verb(ctx_menu, id - ID_FIRST)
        } else {
            None
        };

        let submenu = if !mii.hSubMenu.is_invalid() {
            Some(enumerate_menu(ctx_menu, mii.hSubMenu)?)
        } else {
            None
        };

        items.push(MenuItem {
            id,
            label,
            command_string,
            is_separator: false,
            is_disabled: mii.fState.contains(MFS_DISABLED),
            is_checked: mii.fState.contains(MFS_CHECKED),
            is_default: mii.fState.contains(MFS_DEFAULT),
            submenu,
        });
    }

    Ok(items)
}

/// Try to get the ANSI verb string for a command at the given offset.
fn get_verb(ctx_menu: &IContextMenu, offset: u32) -> Option<String> {
    let mut buf = [0u8; 256];
    // SAFETY: `GetCommandString` with `GCS_VERBA` writes an ANSI string
    // into `buf`. We provide `buf.len()` as the maximum size. The shell
    // handler returns `S_OK` if a verb is available, otherwise an error.
    unsafe {
        ctx_menu
            .GetCommandString(
                offset as usize,
                GCS_VERBA,
                None,
                PSTR(buf.as_mut_ptr()),
                buf.len() as u32,
            )
            .ok()?;
    }
    let s = crate::util::ansi_buf_to_string(&buf);
    if s.is_empty() {
        None
    } else {
        Some(s)
    }
}

/// Retrieve `MenuItem` metadata for a specific command ID in the menu.
fn get_menu_item_info_for_id(
    ctx_menu: &IContextMenu,
    hmenu: HMENU,
    command_id: u32,
) -> Result<MenuItem> {
    let mut mii = MENUITEMINFOW {
        cbSize: std::mem::size_of::<MENUITEMINFOW>() as u32,
        fMask: MIIM_ID | MIIM_FTYPE | MIIM_STATE | MIIM_STRING,
        ..Default::default()
    };

    // First pass: get the string length.
    // SAFETY: `GetMenuItemInfoW` with `fByPosition = false` looks up by
    // command ID.
    unsafe {
        GetMenuItemInfoW(hmenu, command_id, false, &mut mii).map_err(Error::GetMenuItemInfo)?;
    }

    // Second pass: read the actual string.
    let mut label_buf = vec![0u16; (mii.cch + 1) as usize];
    mii.dwTypeData = windows::core::PWSTR(label_buf.as_mut_ptr());
    mii.cch += 1;
    // SAFETY: `label_buf` is large enough.
    unsafe {
        let _ = GetMenuItemInfoW(hmenu, command_id, false, &mut mii);
    }

    let label =
        String::from_utf16_lossy(&label_buf[..mii.cch as usize]).replace('&', "");

    let command_string = if command_id >= ID_FIRST {
        get_verb(ctx_menu, command_id - ID_FIRST)
    } else {
        None
    };

    Ok(MenuItem {
        id: command_id,
        label,
        command_string,
        is_separator: false,
        is_disabled: mii.fState.contains(MFS_DISABLED),
        is_checked: mii.fState.contains(MFS_CHECKED),
        is_default: mii.fState.contains(MFS_DEFAULT),
        submenu: None,
    })
}

/// Check if the clipboard has file data and inject "Paste" at the top of the menu.
fn inject_clipboard_items(hmenu: HMENU) {
    let has_files = clipboard_has_files();

    if has_files {
        // Insert a separator + "Paste" at position 0 (top of menu)
        let paste_label: Vec<u16> = "貼り付け(V)\0".encode_utf16().collect();
        let mii = MENUITEMINFOW {
            cbSize: std::mem::size_of::<MENUITEMINFOW>() as u32,
            fMask: MIIM_ID | MIIM_STRING | MIIM_FTYPE,
            fType: MFT_STRING,
            wID: ID_PASTE_INJECTED,
            dwTypeData: windows::core::PWSTR(paste_label.as_ptr() as *mut _),
            cch: paste_label.len() as u32 - 1,
            ..Default::default()
        };
        // SAFETY: `hmenu` is a valid menu handle. We insert at position 0.
        unsafe {
            let _ = InsertMenuItemW(hmenu, 0, true, &mii);
        }

        // Add separator after Paste
        let sep = MENUITEMINFOW {
            cbSize: std::mem::size_of::<MENUITEMINFOW>() as u32,
            fMask: MIIM_FTYPE,
            fType: MFT_SEPARATOR,
            ..Default::default()
        };
        // SAFETY: Insert separator at position 1 (after Paste).
        unsafe {
            let _ = InsertMenuItemW(hmenu, 1, true, &sep);
        }
    }
}

/// Check if the system clipboard contains file data (CF_HDROP).
fn clipboard_has_files() -> bool {
    // SAFETY: `OleGetClipboard` retrieves the current OLE clipboard data object.
    let data_obj = unsafe { OleGetClipboard() };
    let data_obj = match data_obj {
        Ok(d) => d,
        Err(_) => return false,
    };

    let fmt = FORMATETC {
        cfFormat: CF_HDROP,
        ptd: std::ptr::null_mut(),
        dwAspect: 1, // DVASPECT_CONTENT
        lindex: -1,
        tymed: 1, // TYMED_HGLOBAL
    };

    // SAFETY: `QueryGetData` checks if the data object supports the given format.
    let result = unsafe { data_obj.QueryGetData(&fmt) };
    result.is_ok()
}

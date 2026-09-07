//! # win-context-menu
//!
//! Show and interact with Windows Explorer context menus programmatically.
//!
//! This crate provides a safe Rust API to display the native Windows shell
//! context menu for files and folders, enumerate menu items, and execute
//! selected commands — all without requiring an Explorer window.
//!
//! ## Quick Start
//!
//! ```no_run
//! use win_context_menu::{init_com, show_context_menu};
//!
//! fn main() -> win_context_menu::Result<()> {
//!     let _com = init_com()?;
//!     if let Some(selected) = show_context_menu(r"C:\Windows\notepad.exe")? {
//!         println!("Selected: {}", selected.menu_item().label);
//!         selected.execute()?;
//!     }
//!     Ok(())
//! }
//! ```
//!
//! ## Threading
//!
//! All operations require COM to be initialized in **single-threaded apartment
//! (STA)** mode on the calling thread. Call [`init_com`] once at the start of
//! your thread. The returned [`ComGuard`] must be kept alive for the duration
//! of your context-menu work and **must not be moved to another thread**.
//!
//! ## Feature flags
//!
//! | Feature   | Description |
//! |-----------|-------------|
//! | `ffi`     | Enable C-compatible FFI exports (adds `serde_json` dependency) |
//! | `serde`   | Derive `Serialize` / `Deserialize` for [`MenuItem`] |
//! | `tracing` | *(reserved for future use)* |

mod com;
mod context_menu;
mod error;
#[cfg(feature = "ffi")]
mod ffi;
mod hidden_window;
mod invoke;
mod menu_items;
mod shell_item;
mod util;

pub use com::ComGuard;
pub use context_menu::ContextMenu;
pub use error::{Error, Result};
pub use menu_items::{InvokeParams, MenuItem, SelectedItem};
pub use shell_item::ShellItems;

#[cfg(feature = "ffi")]
pub use ffi::*;

use std::path::Path;

/// Initialize COM for the current thread in STA mode.
///
/// Returns a guard that calls `CoUninitialize` on drop. Keep it alive for the
/// lifetime of your context-menu operations.
///
/// # Errors
///
/// Returns [`Error::ComInit`] if COM has already been initialized with an
/// incompatible threading model.
///
/// # Example
///
/// ```no_run
/// let _com = win_context_menu::init_com()?;
/// // … use ContextMenu, ShellItems, etc. …
/// # Ok::<(), win_context_menu::Error>(())
/// ```
pub fn init_com() -> Result<ComGuard> {
    ComGuard::new()
}

/// Convenience: show a context menu for a single path at the cursor position.
///
/// Equivalent to:
/// ```no_run
/// # use win_context_menu::*;
/// # let path = r"C:\Windows\notepad.exe";
/// let items = ShellItems::from_path(path)?;
/// ContextMenu::new(items)?.show()?;
/// # Ok::<(), Error>(())
/// ```
pub fn show_context_menu(path: impl AsRef<Path>) -> Result<Option<SelectedItem>> {
    let items = ShellItems::from_path(path)?;
    ContextMenu::new(items)?.show()
}

/// Convenience: show an extended context menu (Shift+right-click) at the
/// cursor position.
pub fn show_extended_context_menu(path: impl AsRef<Path>) -> Result<Option<SelectedItem>> {
    let items = ShellItems::from_path(path)?;
    ContextMenu::new(items)?.extended(true).show()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_com_guard_init_and_drop() {
        let guard = init_com();
        assert!(guard.is_ok());
    }

    #[test]
    fn test_shell_items_from_path() {
        let _com = init_com().unwrap();
        let result = ShellItems::from_path(r"C:\Windows\notepad.exe");
        assert!(result.is_ok());
    }

    #[test]
    fn test_shell_items_nonexistent() {
        let _com = init_com().unwrap();
        let result = ShellItems::from_path(r"C:\This\Does\Not\Exist\file.txt");
        assert!(result.is_err());
    }

    #[test]
    fn test_enumerate_notepad() {
        let _com = init_com().unwrap();
        let items = ShellItems::from_path(r"C:\Windows\notepad.exe").unwrap();
        let menu = ContextMenu::new(items).unwrap();
        let entries = menu.enumerate().unwrap();

        assert!(!entries.is_empty(), "Context menu should have items");

        let has_properties = entries.iter().any(|e| {
            e.command_string
                .as_deref()
                .is_some_and(|s| s == "properties")
        });
        assert!(has_properties, "Should have a 'properties' verb");
    }

    #[test]
    fn test_enumerate_extended() {
        let _com = init_com().unwrap();
        let items = ShellItems::from_path(r"C:\Windows\notepad.exe").unwrap();
        let normal = ContextMenu::new(items).unwrap().enumerate().unwrap();

        let items2 = ShellItems::from_path(r"C:\Windows\notepad.exe").unwrap();
        let extended = ContextMenu::new(items2)
            .unwrap()
            .extended(true)
            .enumerate()
            .unwrap();

        assert!(
            extended.len() >= normal.len(),
            "Extended menu should have at least as many items as normal"
        );
    }

    #[test]
    fn test_folder_background() {
        let _com = init_com().unwrap();
        let result = ShellItems::folder_background(r"C:\Windows");
        assert!(result.is_ok());
    }

    #[test]
    fn test_multi_paths_same_folder() {
        let _com = init_com().unwrap();
        let result = ShellItems::from_paths(&[
            r"C:\Windows\notepad.exe",
            r"C:\Windows\regedit.exe",
        ]);
        assert!(result.is_ok());
    }

    #[test]
    fn test_multi_paths_different_folders_fails() {
        let _com = init_com().unwrap();
        let result = ShellItems::from_paths(&[
            r"C:\Windows\notepad.exe",
            r"C:\Users",
        ]);
        assert!(result.is_err());
    }
}

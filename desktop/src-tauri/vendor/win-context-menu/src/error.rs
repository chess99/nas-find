//! Error types for the `win-context-menu` crate.

use std::path::PathBuf;

/// Errors that can occur when working with Windows shell context menus.
#[derive(Debug, thiserror::Error)]
pub enum Error {
    /// COM runtime failed to initialize.
    #[error("COM initialization failed: {0}")]
    ComInit(windows::core::Error),

    /// A filesystem path could not be resolved to a shell item (PIDL).
    #[error("Windows 无法识别此路径：{path}（{source}）")]
    ParsePath {
        /// The path that failed to resolve.
        path: PathBuf,
        /// The underlying Windows error.
        #[source]
        source: windows::core::Error,
    },

    /// `SHBindToParent` could not locate the parent `IShellFolder`.
    #[error("Failed to bind to parent shell folder: {0}")]
    BindToParent(windows::core::Error),

    /// Multiple paths were provided that do not share a common parent folder.
    #[error("Selected paths do not share a common parent folder")]
    NoCommonParent,

    /// Do not expose a menu for only a subset of the selected objects.
    #[error("系统未能完整载入所选项目，未打开部分选择的菜单。请检查文件是否仍可访问后重试")]
    IncompleteSelection,

    /// The shell folder refused to hand out an `IContextMenu` interface.
    #[error("Failed to get context menu interface: {0}")]
    GetContextMenu(windows::core::Error),

    /// `IContextMenu::QueryContextMenu` failed.
    #[error("QueryContextMenu failed: {0}")]
    QueryContextMenu(windows::core::Error),

    /// `TrackPopupMenu` failed to display the context menu.
    #[error("TrackPopupMenu failed: {0}")]
    TrackPopupMenu(windows::core::Error),

    /// `IContextMenu::InvokeCommand` failed to execute the selected command.
    #[error("Failed to invoke command: {0}")]
    InvokeCommand(windows::core::Error),

    /// `IContextMenu::GetCommandString` failed.
    #[error("Failed to get command string: {0}")]
    GetCommandString(windows::core::Error),

    /// `GetMenuItemInfoW` failed to retrieve menu item metadata.
    #[error("Failed to get menu item info: {0}")]
    GetMenuItemInfo(windows::core::Error),

    /// Failed to create the hidden owner window used for submenu rendering.
    #[error("Failed to create hidden window: {0}")]
    CreateWindow(windows::core::Error),

    /// Failed to register the window class for the hidden owner window.
    #[error("Failed to register window class: {0}")]
    RegisterClass(windows::core::Error),

    /// Catch-all for other Windows API errors.
    #[error("Windows API error: {0}")]
    Windows(#[from] windows::core::Error),
}

/// A specialized `Result` type for this crate.
pub type Result<T> = std::result::Result<T, Error>;

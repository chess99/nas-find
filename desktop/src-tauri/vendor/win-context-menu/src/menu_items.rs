//! Data types representing context menu items and user selections.

/// A single item from a context menu.
///
/// Obtained via [`ContextMenu::enumerate`](crate::ContextMenu::enumerate) or
/// as part of a [`SelectedItem`].
#[derive(Debug, Clone, PartialEq, Eq)]
#[cfg_attr(feature = "serde", derive(serde::Serialize, serde::Deserialize))]
pub struct MenuItem {
    /// The raw command ID assigned by the shell handler.
    pub id: u32,
    /// Display label with accelerator characters (`&`) stripped.
    pub label: String,
    /// The verb/command string if available (e.g. `"open"`, `"delete"`,
    /// `"properties"`). Not all items expose a verb.
    pub command_string: Option<String>,
    /// `true` if this item is a visual separator line.
    pub is_separator: bool,
    /// `true` if this item is disabled / grayed out.
    pub is_disabled: bool,
    /// `true` if this item has a check mark.
    pub is_checked: bool,
    /// `true` if this is the default (bold) item.
    pub is_default: bool,
    /// Sub-menu items, if this item opens a submenu.
    pub submenu: Option<Vec<MenuItem>>,
}

impl MenuItem {
    pub(crate) fn separator() -> Self {
        Self {
            id: 0,
            label: String::new(),
            command_string: None,
            is_separator: true,
            is_disabled: false,
            is_checked: false,
            is_default: false,
            submenu: None,
        }
    }
}

impl std::fmt::Display for MenuItem {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        if self.is_separator {
            write!(f, "---separator---")
        } else {
            write!(f, "{}", self.label)?;
            if let Some(ref cmd) = self.command_string {
                write!(f, " [{}]", cmd)?;
            }
            Ok(())
        }
    }
}

/// Boxed closure that executes a context menu command.
pub(crate) type Invoker = Box<dyn FnOnce(Option<InvokeParams>) -> crate::error::Result<()>>;

/// A menu item that was selected by the user via
/// [`ContextMenu::show`](crate::ContextMenu::show) or
/// [`ContextMenu::show_at`](crate::ContextMenu::show_at).
///
/// Call [`execute`](SelectedItem::execute) to run the associated shell command,
/// or inspect [`menu_item`](SelectedItem::menu_item) to decide first.
///
/// The `SelectedItem` keeps the hidden helper window alive until it is dropped
/// or consumed by [`execute`](SelectedItem::execute), ensuring the owner HWND
/// remains valid for commands that display UI (e.g., Properties dialog).
pub struct SelectedItem {
    pub(crate) menu_item: MenuItem,
    pub(crate) command_id: u32,
    pub(crate) invoker: Option<Invoker>,
    /// Prevent the hidden window from being destroyed before execute().
    pub(crate) _hidden_window: Option<crate::hidden_window::HiddenWindow>,
}

impl SelectedItem {
    /// Get the menu item metadata (label, verb, flags, etc.).
    pub fn menu_item(&self) -> &MenuItem {
        &self.menu_item
    }

    /// Get the raw command ID that was returned by `TrackPopupMenu`.
    pub fn command_id(&self) -> u32 {
        self.command_id
    }

    /// Execute the selected command with default parameters.
    ///
    /// This is equivalent to the user clicking the menu item in Explorer.
    /// Consumes `self` because a command can only be invoked once.
    pub fn execute(self) -> crate::error::Result<()> {
        if let Some(invoker) = self.invoker {
            invoker(None)
        } else {
            Ok(())
        }
    }

    /// Execute the selected command with custom parameters.
    ///
    /// Use this to override the working directory or window show state.
    pub fn execute_with(self, params: InvokeParams) -> crate::error::Result<()> {
        if let Some(invoker) = self.invoker {
            invoker(Some(params))
        } else {
            Ok(())
        }
    }
}

impl std::fmt::Debug for SelectedItem {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("SelectedItem")
            .field("menu_item", &self.menu_item)
            .field("command_id", &self.command_id)
            .finish_non_exhaustive()
    }
}

/// Parameters for customizing command invocation.
///
/// Pass to [`SelectedItem::execute_with`] to override defaults.
#[derive(Debug, Clone, Default)]
pub struct InvokeParams {
    /// Working directory for the command. Defaults to the item's parent folder.
    pub directory: Option<String>,
    /// Window show command (`SW_SHOW`, `SW_HIDE`, etc.). Defaults to
    /// `SW_SHOWNORMAL`.
    pub show_cmd: Option<i32>,
}

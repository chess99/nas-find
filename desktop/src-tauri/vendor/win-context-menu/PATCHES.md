# Local fixes to win-context-menu 0.1.4

Source: crates.io `win-context-menu` 0.1.4, upstream <https://github.com/cignoir/win-context-menu>.
Original MIT / Apache-2.0 license files are retained.

- `src/util.rs`: convert `\\?\UNC\server\share` to `\\server\share` before the generic extended-path prefix branch. Upstream produces `UNC\server\share`, which fails Shell parsing for both UNC paths and canonicalized mapped drives.
- `src/hidden_window.rs`: forward `WM_MENUCHAR` to the existing IContextMenu3 handler, as required for menu character handling.
- `src/results_folder.rs` / `src/shell_item.rs`: cross-directory selections use the Windows `IExplorerBrowser` results folder. It is created empty; each selected absolute PIDL is added through `IResultsFolder::AddIDList`, so selecting a folder does not walk or substitute its contents. The native control remains invisible and does not start another process.
- `src/context_menu.rs`: retain the results-folder host through command invocation, after the popup closes. Same-directory selections retain the original fast path. Tests inspect the actual Shell data object for same-name files, mixed types, and folders.

No application-specific menu items or execution commands are added. Recheck these patches when upgrading the dependency.

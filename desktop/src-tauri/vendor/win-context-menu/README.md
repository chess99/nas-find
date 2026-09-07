# win-context-menu

[![Crates.io](https://img.shields.io/crates/v/win-context-menu.svg)](https://crates.io/crates/win-context-menu)
[![Docs.rs](https://docs.rs/win-context-menu/badge.svg)](https://docs.rs/win-context-menu)
[![License](https://img.shields.io/crates/l/win-context-menu.svg)](LICENSE-MIT)

Show and interact with Windows Explorer context menus programmatically from Rust.

## Features

- Display native Windows shell context menus for files and folders
- Support for single file, multi-select, and folder background menus
- Extended menu support (Shift+right-click equivalent)
- Enumerate menu items without displaying the menu
- Execute selected commands
- Full submenu support via `IContextMenu2` / `IContextMenu3`
- Optional C FFI layer for use from other languages (Electron, C#, C++, etc.)
- Optional serde support for menu item serialization

## Quick Start

```rust
use win_context_menu::{init_com, show_context_menu};

fn main() -> win_context_menu::Result<()> {
    let _com = init_com()?;
    if let Some(selected) = show_context_menu(r"C:\Windows\notepad.exe")? {
        println!("Selected: {}", selected.menu_item().label);
        selected.execute()?;
    }
    Ok(())
}
```

## Builder API

```rust
use win_context_menu::{init_com, ContextMenu, ShellItems};

fn main() -> win_context_menu::Result<()> {
    let _com = init_com()?;

    // Single file
    let items = ShellItems::from_path(r"C:\Windows\notepad.exe")?;
    let menu = ContextMenu::new(items)?
        .extended(true);  // Shift+right-click

    if let Some(selected) = menu.show()? {
        selected.execute()?;
    }

    // Multiple files (must share the same parent folder)
    let items = ShellItems::from_paths(&[
        r"C:\Windows\notepad.exe",
        r"C:\Windows\regedit.exe",
    ])?;
    ContextMenu::new(items)?.show()?;

    // Folder background (right-click on empty space)
    let items = ShellItems::folder_background(r"C:\Windows")?;
    ContextMenu::new(items)?.show()?;

    Ok(())
}
```

## Enumerate Menu Items

```rust
use win_context_menu::{init_com, ContextMenu, ShellItems};

fn main() -> win_context_menu::Result<()> {
    let _com = init_com()?;
    let items = ShellItems::from_path(r"C:\Windows\notepad.exe")?;
    let menu = ContextMenu::new(items)?;

    for item in menu.enumerate()? {
        if !item.is_separator {
            println!("{} (verb: {:?})", item.label, item.command_string);
        }
    }
    Ok(())
}
```

## FFI (C / C++ / Electron)

Enable the `ffi` feature to expose C-compatible functions:

```toml
[dependencies]
win-context-menu = { version = "0.1", features = ["ffi"] }
```

### C usage example

```c
#include <stdint.h>

// Opaque types
typedef struct FfiComGuard FfiComGuard;
typedef struct {
    uint32_t command_id;
    char*    label;
    char*    verb;
} FfiSelectedItem;

// Functions exported by win-context-menu
FfiComGuard*     wcm_com_init(void);
void             wcm_com_uninit(FfiComGuard* guard);
FfiSelectedItem* wcm_show_context_menu(const char* path, int x, int y);
void             wcm_free_selected(FfiSelectedItem* item);
char*            wcm_enumerate_menu(const char* path, bool extended);
void             wcm_free_string(char* s);
const char*      wcm_last_error(void);  // thread-local error message

int main(void) {
    FfiComGuard* com = wcm_com_init();
    if (!com) return 1;

    FfiSelectedItem* sel = wcm_show_context_menu("C:\\Windows\\notepad.exe", 100, 100);
    if (sel) {
        printf("Selected: %s [%s]\n", sel->label, sel->verb ? sel->verb : "(none)");
        wcm_free_selected(sel);
    } else {
        const char* err = wcm_last_error();
        if (err) printf("Error: %s\n", err);
    }

    wcm_com_uninit(com);
    return 0;
}
```

## Threading

All operations require COM to be initialized in **single-threaded apartment (STA)** mode. Call `init_com()` once per thread and keep the returned `ComGuard` alive. Do not move the guard to another thread.

## Feature Flags

| Feature   | Description |
|-----------|-------------|
| `ffi`     | Enable C-compatible FFI exports (adds `serde_json` dependency) |
| `serde`   | Derive `Serialize` / `Deserialize` for `MenuItem` |
| `tracing` | *(reserved for future use)* |

## Requirements

- Windows 10 or later
- Rust 1.80+ (2021 edition)

## License

Licensed under either of [Apache License, Version 2.0](LICENSE-APACHE) or [MIT license](LICENSE-MIT) at your option.

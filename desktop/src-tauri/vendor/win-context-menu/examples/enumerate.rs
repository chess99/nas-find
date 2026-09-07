use win_context_menu::{init_com, ContextMenu, MenuItem, ShellItems};

fn main() -> win_context_menu::Result<()> {
    let _com = init_com()?;

    let path = std::env::args()
        .nth(1)
        .unwrap_or_else(|| r"C:\Windows\notepad.exe".to_string());

    println!("Enumerating context menu items for: {}\n", path);

    let items = ShellItems::from_path(&path)?;
    let menu = ContextMenu::new(items)?;
    let entries = menu.enumerate()?;

    print_items(&entries, 0);

    Ok(())
}

fn print_items(items: &[MenuItem], depth: usize) {
    let indent = "  ".repeat(depth);
    for item in items {
        if item.is_separator {
            println!("{}---", indent);
        } else {
            let flags = [
                item.is_default.then_some("default"),
                item.is_disabled.then_some("disabled"),
                item.is_checked.then_some("checked"),
            ]
            .into_iter()
            .flatten()
            .collect::<Vec<_>>()
            .join(",");

            let verb = item
                .command_string
                .as_deref()
                .map(|v| format!(" [{}]", v))
                .unwrap_or_default();

            let flag_str = if flags.is_empty() {
                String::new()
            } else {
                format!(" ({})", flags)
            };

            println!(
                "{}{}{}{} (id={})",
                indent, item.label, verb, flag_str, item.id
            );

            if let Some(ref sub) = item.submenu {
                print_items(sub, depth + 1);
            }
        }
    }
}

use win_context_menu::{init_com, ContextMenu, ShellItems};

fn main() -> win_context_menu::Result<()> {
    let _com = init_com()?;

    let path = std::env::args()
        .nth(1)
        .unwrap_or_else(|| r"C:\Windows\notepad.exe".to_string());

    println!("Showing context menu for: {}", path);

    let items = ShellItems::from_path(&path)?;
    let menu = ContextMenu::new(items)?;

    match menu.show()? {
        Some(selected) => {
            println!(
                "Selected: {} (verb: {:?})",
                selected.menu_item().label,
                selected.menu_item().command_string
            );
            selected.execute()?;
        }
        None => println!("No item selected."),
    }

    Ok(())
}

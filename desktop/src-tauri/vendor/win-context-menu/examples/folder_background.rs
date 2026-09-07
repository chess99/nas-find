use win_context_menu::{init_com, ContextMenu, ShellItems};

fn main() -> win_context_menu::Result<()> {
    let _com = init_com()?;

    let folder = std::env::args()
        .nth(1)
        .unwrap_or_else(|| r"C:\Windows".to_string());

    println!("Showing background context menu for folder: {}", folder);

    let items = ShellItems::folder_background(&folder)?;
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

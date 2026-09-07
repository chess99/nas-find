use win_context_menu::{init_com, ContextMenu, ShellItems};

fn main() -> win_context_menu::Result<()> {
    let _com = init_com()?;

    let args: Vec<String> = std::env::args().skip(1).collect();
    let paths: Vec<String> = if args.is_empty() {
        vec![
            r"C:\Windows\notepad.exe".to_string(),
            r"C:\Windows\regedit.exe".to_string(),
        ]
    } else {
        args
    };

    println!("Showing context menu for {} items:", paths.len());
    for p in &paths {
        println!("  - {}", p);
    }

    let items = ShellItems::from_paths(&paths)?;
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

fn main() {
    #[cfg(target_os = "macos")]
    {
        println!("cargo:rerun-if-changed=src/file_menu_macos.m");
        cc::Build::new()
            .file("src/file_menu_macos.m")
            .flag("-fobjc-arc")
            .compile("nas_find_file_menu");
        println!("cargo:rustc-link-lib=framework=AppKit");
        println!("cargo:rustc-link-lib=framework=Quartz");
        println!("cargo:rustc-link-lib=framework=UniformTypeIdentifiers");
    }
    tauri_build::build()
}

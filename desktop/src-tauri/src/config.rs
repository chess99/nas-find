use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(default)]
pub struct Config {
    pub server: String,
    pub mount_path: String,
    pub share: String,
    pub drive: String,
    pub prefer_drive: bool,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            server: String::new(),
            mount_path: String::new(),
            share: String::new(),
            drive: String::new(),
            prefer_drive: false,
        }
    }
}

pub fn relative(value: &str) -> Result<String, String> {
    if value.is_empty() || value.len() > 30000 {
        return Err("文件路径无效".into());
    }
    for part in value.split('/') {
        if part.is_empty()
            || part == "."
            || part == ".."
            || part.ends_with([' ', '.'])
            || part.chars().any(|c| c < ' ' || "\\:<>\"|?*".contains(c))
        {
            return Err("此文件名无法直接通过 Windows 访问".into());
        }
        let base = part.split('.').next().unwrap().to_ascii_uppercase();
        if ["CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$"].contains(&base.as_str())
            || (base.len() == 4
                && (base.starts_with("COM") || base.starts_with("LPT"))
                && matches!(base.as_bytes()[3], b'1'..=b'9'))
        {
            return Err("路径包含 Windows 保留名称".into());
        }
    }
    Ok(value.replace('/', "\\"))
}

impl Config {
    pub fn import_connection(current: &Self, value: &serde_json::Value) -> Result<Self, String> {
        let mut result = current.clone();
        if let Some(incoming) = value.get("client_config") {
            let incoming: Self = serde_json::from_value(incoming.clone())
                .map_err(|_| "连接文件中的 client_config 无效")?;
            if !result.share.eq_ignore_ascii_case(&incoming.share) {
                result.mount_path = incoming.mount_path.clone();
                result.drive = incoming.drive;
                result.prefer_drive = incoming.prefer_drive;
            }
            if result.mount_path.is_empty() {
                result.mount_path = incoming.mount_path;
            }
            result.server = incoming.server;
            result.share = incoming.share;
        }
        if let Some(url) = value.get("url").and_then(|v| v.as_str()) {
            result.server = url.into();
        }
        result
            .validated()
            .map_err(|e| format!("{e}；新机器请使用包含 client_config 的连接文件，或先手动配置"))
    }

    pub fn validated(mut self) -> Result<Self, String> {
        if self.server.trim().is_empty() || self.share.trim().is_empty() {
            return Err("请填写搜索服务地址和 Samba 共享根路径".into());
        }
        let server = url::Url::parse(self.server.trim()).map_err(|_| "服务地址格式不正确")?;
        if !matches!(server.scheme(), "http" | "https")
            || server.host_str().is_none()
            || !server.username().is_empty()
            || server.password().is_some()
            || server.query().is_some()
            || server.fragment().is_some()
            || server.path() != "/"
        {
            return Err("服务地址应为 http://主机:端口，不能包含账号或额外路径".into());
        }
        self.server = server.as_str().trim_end_matches('/').into();
        self.share = self.share.trim().trim_end_matches('\\').into();
        let tail = self
            .share
            .strip_prefix(r"\\")
            .ok_or("共享路径应为 \\\\服务器\\共享名")?;
        let parts: Vec<_> = tail.split('\\').collect();
        if parts.len() != 2
            || parts[0].is_empty()
            || matches!(parts[0], "." | "?")
            || parts[0].chars().any(|c| c < ' ' || "/:<>\"|?*".contains(c))
        {
            return Err("请输入共享根路径，例如 \\\\nas.example.internal\\files".into());
        }
        relative(parts[1])?;
        #[cfg(target_os = "macos")]
        {
            self.prefer_drive = false;
        }
        self.drive = self
            .drive
            .trim()
            .trim_end_matches(['\\', '/'])
            .to_ascii_uppercase();
        if !self.drive.is_empty()
            && !(self.drive.len() == 2
                && self.drive.as_bytes()[0].is_ascii_uppercase()
                && self.drive.ends_with(':'))
        {
            return Err("映射盘请输入 Z: 这样的盘符".into());
        }
        if self.prefer_drive && self.drive.is_empty() {
            return Err("优先映射盘时需要填写盘符".into());
        }
        if cfg!(target_os = "macos") {
            self.prefer_drive = false;
            if self.mount_path == "/" {
                return Err("不能使用文件系统根目录作为 SMB 挂载路径".into());
            }
            self.mount_path = self.mount_path.trim_end_matches('/').into();
            if !self.mount_path.is_empty()
                && (!self.mount_path.starts_with('/')
                    || self.mount_path.contains(['\0', '\r', '\n'])
                    || self
                        .mount_path
                        .split('/')
                        .skip(1)
                        .any(|p| p.is_empty() || p == "." || p == ".."))
            {
                return Err("挂载路径应为绝对目录，例如 /Volumes/files".into());
            }
        }
        Ok(self)
    }

    pub fn resolve(&self, path: &str, mapping: Option<&str>) -> Result<(String, bool), String> {
        #[cfg(target_os = "macos")]
        {
            let _ = mapping;
            if self.mount_path.is_empty() {
                return Err("请先在 Finder 连接 SMB，并填写本机挂载路径".into());
            }
            return Ok((
                format!("{}/{}", self.mount_path, relative_posix(path)?),
                false,
            ));
        }
        #[cfg(windows)]
        {
            let tail = relative(path)?;
            let use_drive = self.prefer_drive
                && mapping
                    .is_some_and(|m| m.trim_end_matches('\\').eq_ignore_ascii_case(&self.share));
            let root = if use_drive { &self.drive } else { &self.share };
            Ok((format!("{root}\\{tail}"), self.prefer_drive && !use_drive))
        }
    }
}

#[cfg(test)]
pub fn fixture() -> Config {
    Config {
        server: "http://nas.example.internal:8765".into(),
        mount_path: String::new(),
        share: r"\\nas.example.internal\files".into(),
        drive: "Z:".into(),
        prefer_drive: true,
    }
}

#[cfg(test)]
pub fn live_config(access: Option<&serde_json::Value>) -> Config {
    let value = if let Some(value) = access.and_then(|a| a.get("client_config")) {
        value.clone()
    } else {
        let path = std::env::var("NAS_FIND_CLIENT_CONFIG")
            .expect("Set NAS_FIND_CLIENT_CONFIG to an explicit client configuration JSON file");
        serde_json::from_slice(&std::fs::read(path).unwrap()).unwrap()
    };
    serde_json::from_value::<Config>(value)
        .unwrap()
        .validated()
        .unwrap()
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn path_boundaries_and_unicode() {
        assert_eq!(
            relative("资料/财报 2026.xlsx").unwrap(),
            "资料\\财报 2026.xlsx"
        );
        for p in [
            "../x",
            "/x",
            "a//b",
            "a/./b",
            "C:/x",
            "a\\b",
            "x:ads",
            "x\0",
            "CON.txt",
            "lpt1",
            "a.",
            "a ",
            "//host/share",
        ] {
            assert!(relative(p).is_err(), "{p:?}");
        }
    }
    #[test]
    #[cfg(windows)]
    fn wrong_drive_falls_back_to_correct_unc() {
        let c = fixture();
        assert_eq!(
            c.resolve("a.txt", Some(&c.share)).unwrap(),
            (r"Z:\a.txt".into(), false)
        );
        assert_eq!(
            c.resolve("a.txt", Some(r"\\other\share")).unwrap(),
            (r"\\nas.example.internal\files\a.txt".into(), true)
        );
        assert_eq!(c.resolve("a.txt", None).unwrap().1, true);
    }
    #[test]
    fn invalid_config_rejected() {
        for server in [
            "file:///C:/",
            "http://user:pw@host",
            "http://host/api",
            "http://host/?q=x",
        ] {
            assert!(Config {
                server: server.into(),
                ..fixture()
            }
            .validated()
            .is_err());
        }
        for share in [r"\\?\C:", r"\\.\pipe", r"C:\data", r"\\host\share\extra"] {
            assert!(Config {
                share: share.into(),
                ..fixture()
            }
            .validated()
            .is_err());
        }
    }

    #[test]
    fn first_run_is_empty_and_saved_configuration_survives() {
        let empty = Config::default();
        assert!(empty.server.is_empty() && empty.share.is_empty() && !empty.prefer_drive);
        assert!(empty.validated().is_err());
        let saved = fixture();
        let restored: Config =
            serde_json::from_str(&serde_json::to_string(&saved).unwrap()).unwrap();
        assert_eq!(restored.validated().unwrap().share, saved.share);
        let old_access = serde_json::json!({"url": saved.server});
        assert!(Config::import_connection(&Config::default(), &old_access).is_err());
        let migrated = Config::import_connection(&saved, &old_access).unwrap();
        assert_eq!(migrated.drive, "Z:");
        let incoming = serde_json::json!({"client_config":{"server":saved.server,"share":saved.share,"prefer_drive":false}});
        assert_eq!(
            Config::import_connection(&saved, &incoming).unwrap().drive,
            "Z:"
        );
        assert!(Config::import_connection(&Config::default(), &incoming).is_ok());
    }
}

pub fn relative_posix(value: &str) -> Result<String, String> {
    if value.is_empty()
        || value.len() > 30000
        || value.contains('\0')
        || value
            .split('/')
            .any(|p| p.is_empty() || p == "." || p == "..")
    {
        return Err("文件路径无效".into());
    }
    Ok(value.into())
}

pub fn relative_native(value: &str) -> Result<String, String> {
    if cfg!(target_os = "macos") {
        relative_posix(value)
    } else {
        relative(value)
    }
}

#[cfg(all(test, target_os = "macos"))]
mod mac_tests {
    use super::*;
    #[test]
    fn posix_names_and_mount_boundaries() {
        let config = Config {
            mount_path: "/Volumes/共享文件/".into(),
            ..fixture()
        }
        .validated()
        .unwrap();
        assert_eq!(
            config.resolve("资料/CON:财报?.txt", None).unwrap().0,
            "/Volumes/共享文件/资料/CON:财报?.txt"
        );
        for path in [
            "../outside",
            "/outside",
            "a/../b",
            "a//b",
            "a/./b",
            "bad\0name",
        ] {
            assert!(config.resolve(path, None).is_err());
        }
        for mount in [
            "/",
            "relative/path",
            "/Volumes/../tmp",
            "/Volumes//files",
            "/Volumes/./files",
        ] {
            assert!(Config {
                mount_path: mount.into(),
                ..fixture()
            }
            .validated()
            .is_err());
        }
        assert!(fixture().resolve("file.txt", None).is_err());
    }
}

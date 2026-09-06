use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(default)]
pub struct Config {
    pub server: String,
    pub share: String,
    pub drive: String,
    pub prefer_drive: bool,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            server: "http://192.168.0.104:8765".into(),
            share: r"\\192.168.0.104\Disk1".into(),
            drive: "Z:".into(),
            prefer_drive: true,
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
    pub fn validated(mut self) -> Result<Self, String> {
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
            return Err("请输入共享根路径，例如 \\\\192.168.0.104\\Disk1".into());
        }
        relative(parts[1])?;
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
        Ok(self)
    }

    pub fn resolve(&self, path: &str, mapping: Option<&str>) -> Result<(String, bool), String> {
        let tail = relative(path)?;
        let use_drive = self.prefer_drive
            && mapping.is_some_and(|m| m.trim_end_matches('\\').eq_ignore_ascii_case(&self.share));
        let root = if use_drive { &self.drive } else { &self.share };
        Ok((format!("{root}\\{tail}"), self.prefer_drive && !use_drive))
    }
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
    fn wrong_drive_falls_back_to_correct_unc() {
        let c = Config::default();
        assert_eq!(
            c.resolve("a.txt", Some(&c.share)).unwrap(),
            (r"Z:\a.txt".into(), false)
        );
        assert_eq!(
            c.resolve("a.txt", Some(r"\\other\share")).unwrap(),
            (r"\\192.168.0.104\Disk1\a.txt".into(), true)
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
                ..Config::default()
            }
            .validated()
            .is_err());
        }
        for share in [r"\\?\C:", r"\\.\pipe", r"C:\data", r"\\host\share\extra"] {
            assert!(Config {
                share: share.into(),
                ..Config::default()
            }
            .validated()
            .is_err());
        }
    }
}

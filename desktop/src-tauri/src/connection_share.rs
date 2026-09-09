use crate::{config::Config, native};
use serde_json::{json, Value};

pub fn payload(config: &Config, password: Option<&[u8]>) -> Result<Value, String> {
    let encrypted = password.ok_or("此连接没有保存密码，分享暂不可用；下次正常连接后会自动保存")?;
    let server = url::Url::parse(&config.server).map_err(|_| "保存的服务地址无效")?;
    let host = server.host_str().unwrap_or("").trim_matches(['[', ']']);
    if host == "localhost" || host.ends_with(".localhost") || host == "0.0.0.0" || host == "::" || host == "::1"
        || host.parse::<std::net::IpAddr>().is_ok_and(|ip| ip.is_loopback())
    {
        return Err("此地址只适用于本机；请先使用手机可访问的 NAS 地址连接，再分享".into());
    }
    let password = String::from_utf8(native::protect(encrypted, true)?)
        .map_err(|_| "保存的登录信息不可用，请重新连接")?;
    if password.is_empty() { return Err("没有可分享的已保存密码".into()); }
    // Only portable NAS access information crosses the bridge; no SMB or machine-specific paths.
    Ok(json!({"server":config.server,"password":password}))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn unavailable_password_never_becomes_a_prompt_or_empty_secret() {
        let config = Config { server: "http://nas.example.internal:8765".into(), ..Config::default() };
        assert!(payload(&config, None).unwrap_err().contains("没有保存密码"));
    }

    #[test]
    fn only_share_portable_connection_and_reject_loopback() {
        let secret = "synthetic-share-password-中文";
        let protected = native::protect(secret.as_bytes(), false).unwrap();
        let config = Config { server: "http://nas.example.internal:8765".into(), share: r"\\nas\files".into(), drive: "Z:".into(), ..Config::default() };
        let value = payload(&config, Some(&protected)).unwrap();
        assert_eq!(value.as_object().unwrap().len(), 2);
        assert_eq!(value["password"], secret);
        assert_eq!(value["server"], config.server);
        for server in ["http://localhost:8765", "http://127.0.0.1:8765", "http://[::1]:8765"] {
            assert!(payload(&Config { server: server.into(), ..config.clone() }, Some(&protected)).is_err());
        }
        native::forget(&protected);
    }
}

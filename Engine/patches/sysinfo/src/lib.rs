//! The part of `sysinfo`'s API that `librespot-core` uses. Reading the OS version needs platform
//! calls the real crate gets wrong on iOS, and the host already knows it, so the host sets it.

use std::sync::RwLock;

static OS_VERSION: RwLock<Option<String>> = RwLock::new(None);

/// Sets the version reported as the OS version ("18.2" on iOS, "15" on Android).
pub fn set_os_version(version: &str) {
    let version = version.trim();
    let mut slot = OS_VERSION.write().unwrap_or_else(std::sync::PoisonError::into_inner);
    *slot = (!version.is_empty()).then(|| version.to_owned());
}

#[derive(Debug, Clone, Copy, Default)]
pub struct System;

impl System {
    /// The host's version if set. Otherwise, on phones, a version Spotify accepts: an unknown
    /// one makes its login and client-token requests fail.
    pub fn os_version() -> Option<String> {
        let set = OS_VERSION
            .read()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .clone();
        set.or_else(|| fallback().map(str::to_owned))
    }

    pub fn kernel_version() -> Option<String> {
        None
    }
}

const fn fallback() -> Option<&'static str> {
    if cfg!(target_os = "ios") {
        Some("17")
    } else if cfg!(target_os = "android") {
        Some("30")
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_set_version_is_reported_and_blank_clears_it() {
        set_os_version(" 18.2 ");
        assert_eq!(System::os_version().as_deref(), Some("18.2"));
        set_os_version("");
        assert_eq!(System::os_version().as_deref(), fallback());
    }
}

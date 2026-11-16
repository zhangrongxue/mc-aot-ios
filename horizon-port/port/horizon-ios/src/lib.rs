//! C ABI used by the iOS UIKit shell.
//!
//! This first milestone only reports what was compiled and linked. The game
//! entry point will replace it once the client library builds for iOS.

use std::ffi::{CString, c_char};
use std::sync::OnceLock;

static DIAGNOSTICS: OnceLock<CString> = OnceLock::new();

/// Returns a NUL-terminated UTF-8 diagnostics string owned by this library.
///
/// The pointer stays valid for the lifetime of the process.
#[unsafe(no_mangle)]
pub extern "C" fn horizon_ios_diagnostics() -> *const c_char {
    DIAGNOSTICS
        .get_or_init(|| {
            let text = format!(
                "Horizon iOS development shell\n\
                 Rust core: {}\n\
                 Minecraft target: {}\n\
                 Build arch: {}\n\
                 Status: engine linked, game entry not wired yet",
                env!("CARGO_PKG_VERSION"),
                horizon_core::VANILLA_VERSION,
                std::env::consts::ARCH,
            );
            CString::new(text).expect("diagnostics contain no NUL")
        })
        .as_ptr()
}

/// A trivial linked-core check the UIKit shell can display.
#[unsafe(no_mangle)]
pub extern "C" fn horizon_ios_self_test() -> i32 {
    // Read a real value from horizon-core so the linker cannot drop the crate.
    if horizon_core::VANILLA_VERSION.is_empty() {
        1
    } else {
        0
    }
}

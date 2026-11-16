//! C ABI used by the iOS UIKit shell.
//!
//! This first milestone only reports what was compiled and linked. The game
//! entry point will replace it once the client library builds for iOS.

use std::ffi::{CStr, CString, c_char};
use std::path::PathBuf;
use std::sync::OnceLock;

static DIAGNOSTICS: OnceLock<CString> = OnceLock::new();

/// Returns a NUL-terminated UTF-8 diagnostics string owned by this library.
///
/// The pointer stays valid for the lifetime of the process.
#[unsafe(no_mangle)]
pub extern "C" fn horizon_ios_diagnostics() -> *const c_char {
    DIAGNOSTICS
        .get_or_init(|| {
            let mappings = horizon_client_lib::keybinds::all_mappings().count();
            let text = format!(
                "Horizon iOS development shell\n\
                 Rust core: {}\n\
                 Minecraft target: {}\n\
                 Build arch: {}\n\
                 Input mappings: {mappings}\n\
                 Status: client library linked, game entry not wired yet",
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

/// Starts the real game. The caller must not have called `UIApplicationMain`:
/// winit's iOS backend owns that call and the app's lifecycle.
///
/// `resources` must point at a directory prepared by `horizon-prepare`;
/// `game_dir` must be writable (the app's Documents directory).
#[unsafe(no_mangle)]
pub extern "C" fn horizon_ios_run(resources: *const c_char, game_dir: *const c_char) -> i32 {
    if resources.is_null() || game_dir.is_null() {
        return 2;
    }
    let resources = match unsafe { CStr::from_ptr(resources) }.to_str() {
        Ok(path) => PathBuf::from(path),
        Err(_) => return 2,
    };
    let game_dir = match unsafe { CStr::from_ptr(game_dir) }.to_str() {
        Ok(path) => PathBuf::from(path),
        Err(_) => return 2,
    };
    horizon_game::main_ios(resources, game_dir);
}

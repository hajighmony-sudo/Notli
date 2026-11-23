use std::collections::HashMap;
use std::sync::{Arc, Mutex, OnceLock};
use std::sync::atomic::{AtomicI64, Ordering};

use jni::objects::{JObject, JString};
use jni::sys::{jlong, jstring};
use jni::JNIEnv;
use serde::Serialize;

use crate::downloads::{DownloadCommand, DownloadManager};
use crate::engine::PlayerCore;
use crate::model::{BackendEvent, PlayerCommand};

#[derive(Default)]
struct NativeRuntime {
    player: PlayerCore,
    downloads: DownloadManager,
}

// Handles are opaque integers, never raw pointers. Cloning the Arc before an operation
// means destroy() can remove a handle without invalidating an operation already in flight.
static RUNTIMES: OnceLock<Mutex<HashMap<jlong, Arc<Mutex<NativeRuntime>>>>> = OnceLock::new();
static NEXT_HANDLE: AtomicI64 = AtomicI64::new(1);

fn runtimes() -> &'static Mutex<HashMap<jlong, Arc<Mutex<NativeRuntime>>>> {
    RUNTIMES.get_or_init(|| Mutex::new(HashMap::new()))
}

fn java_string(env: &mut JNIEnv<'_>, value: String) -> jstring {
    env.new_string(value)
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn read_string(env: &mut JNIEnv<'_>, value: JString<'_>) -> Result<String, String> {
    env.get_string(&value)
        .map(|value| value.into())
        .map_err(|error| format!("JNI string conversion failed: {error}"))
}

fn json_success<T: Serialize>(value: &T) -> String {
    match serde_json::to_value(value) {
        Ok(value) => serde_json::json!({"ok": true, "value": value}).to_string(),
        Err(error) => json_error(format!("serialization failed: {error}")),
    }
}

fn json_error(message: impl Into<String>) -> String {
    serde_json::json!({"ok": false, "error": message.into()}).to_string()
}

fn with_runtime<T>(
    handle: jlong,
    operation: impl FnOnce(&mut NativeRuntime) -> T,
) -> Result<T, String> {
    if handle == 0 {
        return Err("native engine is closed".to_owned());
    }
    let runtime = runtimes()
        .lock()
        .map_err(|_| "native engine registry lock poisoned".to_owned())?
        .get(&handle)
        .cloned()
        .ok_or_else(|| "native engine handle is invalid or closed".to_owned())?;
    let mut runtime = runtime
        .lock()
        .map_err(|_| "native engine lock poisoned".to_owned())?;
    Ok(operation(&mut runtime))
}

#[no_mangle]
pub extern "system" fn Java_org_oxycblt_auxio_playback_nativecore_RustMusicCore_nativeCreate(
    _env: JNIEnv<'_>,
    _this: JObject<'_>,
) -> jlong {
    let handle = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
    if handle <= 0 {
        return 0;
    }
    match runtimes().lock() {
        Ok(mut registry) => {
            registry.insert(handle, Arc::new(Mutex::new(NativeRuntime::default())));
            handle
        }
        Err(_) => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_org_oxycblt_auxio_playback_nativecore_RustMusicCore_nativeDestroy(
    _env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
) {
    if handle != 0 {
        if let Ok(mut registry) = runtimes().lock() {
            registry.remove(&handle);
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_org_oxycblt_auxio_playback_nativecore_RustMusicCore_nativeDispatch(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    command_json: JString<'_>,
) -> jstring {
    let command_text = match read_string(&mut env, command_json) {
        Ok(value) => value,
        Err(error) => return java_string(&mut env, json_error(error)),
    };
    let command: PlayerCommand = match serde_json::from_str(&command_text) {
        Ok(value) => value,
        Err(error) => return java_string(&mut env, json_error(format!("invalid player command: {error}"))),
    };
    let result = with_runtime(handle, |runtime| {
        runtime.player.handle(command).map_err(|error| format!("{error:?}"))
    });
    let response = match result {
        Ok(Ok(output)) => json_success(&output),
        Ok(Err(error)) | Err(error) => json_error(error),
    };
    java_string(&mut env, response)
}

#[no_mangle]
pub extern "system" fn Java_org_oxycblt_auxio_playback_nativecore_RustMusicCore_nativeBackendEvent(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    event_json: JString<'_>,
) -> jstring {
    let event_text = match read_string(&mut env, event_json) {
        Ok(value) => value,
        Err(error) => return java_string(&mut env, json_error(error)),
    };
    let event: BackendEvent = match serde_json::from_str(&event_text) {
        Ok(value) => value,
        Err(error) => return java_string(&mut env, json_error(format!("invalid backend event: {error}"))),
    };
    let result = with_runtime(handle, |runtime| runtime.player.handle_backend_event(event));
    let response = match result {
        Ok(output) => json_success(&output),
        Err(error) => json_error(error),
    };
    java_string(&mut env, response)
}

#[no_mangle]
pub extern "system" fn Java_org_oxycblt_auxio_playback_nativecore_RustMusicCore_nativeSnapshot(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
) -> jstring {
    let result = with_runtime(handle, |runtime| runtime.player.snapshot());
    let response = match result {
        Ok(snapshot) => json_success(&snapshot),
        Err(error) => json_error(error),
    };
    java_string(&mut env, response)
}

#[no_mangle]
pub extern "system" fn Java_org_oxycblt_auxio_playback_nativecore_RustMusicCore_nativeDownloadCommand(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    command_json: JString<'_>,
) -> jstring {
    let command_text = match read_string(&mut env, command_json) {
        Ok(value) => value,
        Err(error) => return java_string(&mut env, json_error(error)),
    };
    let command: DownloadCommand = match serde_json::from_str(&command_text) {
        Ok(value) => value,
        Err(error) => return java_string(&mut env, json_error(format!("invalid download command: {error}"))),
    };
    let result = with_runtime(handle, |runtime| {
        runtime.downloads.handle(command).map_err(|error| format!("{error:?}"))
    });
    let response = match result {
        Ok(Ok(snapshot)) => json_success(&snapshot),
        Ok(Err(error)) | Err(error) => json_error(error),
    };
    java_string(&mut env, response)
}

#[no_mangle]
pub extern "system" fn Java_org_oxycblt_auxio_playback_nativecore_RustMusicCore_nativeDownloadSnapshot(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
) -> jstring {
    let result = with_runtime(handle, |runtime| runtime.downloads.all());
    let response = match result {
        Ok(snapshot) => json_success(&snapshot),
        Err(error) => json_error(error),
    };
    java_string(&mut env, response)
}

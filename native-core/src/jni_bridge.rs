use std::sync::Mutex;

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

unsafe fn runtime_from_handle<'a>(handle: jlong) -> Result<&'a Mutex<NativeRuntime>, String> {
    if handle == 0 {
        return Err("native engine is closed".to_owned());
    }
    Ok(&*(handle as *const Mutex<NativeRuntime>))
}

#[no_mangle]
pub extern "system" fn Java_org_oxycblt_auxio_playback_nativecore_RustMusicCore_nativeCreate(
    _env: JNIEnv<'_>,
    _this: JObject<'_>,
) -> jlong {
    Box::into_raw(Box::new(Mutex::new(NativeRuntime::default()))) as jlong
}

#[no_mangle]
pub extern "system" fn Java_org_oxycblt_auxio_playback_nativecore_RustMusicCore_nativeDestroy(
    _env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
) {
    if handle != 0 {
        // The Kotlin wrapper guarantees that each handle is destroyed exactly once.
        unsafe { drop(Box::from_raw(handle as *mut Mutex<NativeRuntime>)); }
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
    let result = unsafe { runtime_from_handle(handle) }
        .and_then(|runtime| runtime.lock().map_err(|_| "native engine lock poisoned".to_owned()))
        .map(|mut runtime| runtime.player.handle(command).map_err(|error| format!("{error:?}")));
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
    let result = unsafe { runtime_from_handle(handle) }
        .and_then(|runtime| runtime.lock().map_err(|_| "native engine lock poisoned".to_owned()))
        .map(|mut runtime| runtime.player.handle_backend_event(event));
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
    let result = unsafe { runtime_from_handle(handle) }
        .and_then(|runtime| runtime.lock().map_err(|_| "native engine lock poisoned".to_owned()))
        .map(|runtime| runtime.player.snapshot());
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
    let result = unsafe { runtime_from_handle(handle) }
        .and_then(|runtime| runtime.lock().map_err(|_| "native engine lock poisoned".to_owned()))
        .map(|mut runtime| runtime.downloads.handle(command).map_err(|error| format!("{error:?}")));
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
    let result = unsafe { runtime_from_handle(handle) }
        .and_then(|runtime| runtime.lock().map_err(|_| "native engine lock poisoned".to_owned()))
        .map(|runtime| runtime.downloads.all());
    let response = match result {
        Ok(snapshot) => json_success(&snapshot),
        Err(error) => json_error(error),
    };
    java_string(&mut env, response)
}

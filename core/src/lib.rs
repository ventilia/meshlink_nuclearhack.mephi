

#[cfg(target_os = "android")]
#[allow(non_snake_case)]
#[no_mangle]
pub extern "system" fn JNI_OnLoad(
    _vm: jni::JavaVM,
    _: *mut std::ffi::c_void,
) -> jni::sys::jint {
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Debug)
            .with_tag("MeshLinkCore"),
    );
    log::info!("MeshLink Core v{} loaded", env!("CARGO_PKG_VERSION"));
    jni::JNIVersion::V6.into()
}

pub mod connection;
pub mod crypto;
pub mod peer;
pub mod protocol;
pub mod routing;
pub mod storage;
pub mod transport;
mod jni_bridge;

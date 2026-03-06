// ИСПРАВЛЕНИЕ 1: Arc импортируется ТОЛЬКО здесь, один раз — в самом верху.
// Дублирующий `use std::sync::Arc;` в конце файла удалён.
use jni::objects::{GlobalRef, JByteArray, JClass, JObject, JString};
use jni::sys::{jboolean, jbyteArray, jint, jstring, JNI_FALSE, JNI_TRUE};
use jni::JavaVM;
use jni::JNIEnv;
use once_cell::sync::Lazy;
use std::sync::{Arc, Mutex};

use crate::connection::{ConnectionManager, MessageCallback};
use crate::crypto::KeyPair;
use crate::peer::Peer;
use crate::protocol::Message;
use crate::routing::RoutingTable;
use crate::storage;


static OWN_PEER: Lazy<Mutex<Option<Peer>>> = Lazy::new(|| Mutex::new(None));
static CONNECTION_MANAGER: Lazy<Mutex<ConnectionManager>> =
    Lazy::new(|| Mutex::new(ConnectionManager::new()));
static ROUTING_TABLE: Lazy<Mutex<Option<RoutingTable>>> = Lazy::new(|| Mutex::new(None));
static JAVA_VM: Lazy<Mutex<Option<JavaVM>>> = Lazy::new(|| Mutex::new(None));
static CALLBACK_OBJ: Lazy<Mutex<Option<GlobalRef>>> = Lazy::new(|| Mutex::new(None));


macro_rules! jstr {
    ($env:expr, $s:expr) => {
        match $env.get_string(&$s) {
            Ok(v) => String::from(v),
            Err(e) => {
                log::error!("jstr! failed: {}", e);
                return JNI_FALSE;
            }
        }
    };
}

macro_rules! jstr_ret {
    ($env:expr, $s:expr, $default:expr) => {
        match $env.get_string(&$s) {
            Ok(v) => String::from(v),
            Err(_) => $default,
        }
    };
}

macro_rules! save_vm {
    ($env:expr) => {
        if JAVA_VM.lock().unwrap().is_none() {
            if let Ok(vm) = $env.get_java_vm() {
                *JAVA_VM.lock().unwrap() = Some(vm);
            }
        }
    };
}

macro_rules! new_jstring {
    ($env:expr, $s:expr) => {
        match $env.new_string($s) {
            Ok(s) => s.into_raw(),
            Err(_) => $env.new_string("").unwrap().into_raw(),
        }
    };
}


#[no_mangle]
pub extern "system" fn Java_com_example_meshlink_core_NativeCore_initWithFilesDir(
    mut env: JNIEnv,
    _class: JClass,
    files_dir: JString,
    device_name: JString,
) -> jboolean {
    save_vm!(env);

    let dir = match env.get_string(&files_dir) {
        Ok(s) => String::from(s),
        Err(_) => return JNI_FALSE,
    };
    let name = match env.get_string(&device_name) {
        Ok(s) => String::from(s),
        Err(_) => return JNI_FALSE,
    };

    let keypair = match storage::load_seed(&dir) {
        Some(seed) => {
            log::info!("Loaded existing identity from disk");
            KeyPair::from_seed(&seed)
        }
        None => {
            log::info!("Generating new identity...");
            let kp = KeyPair::generate();
            let seed = kp.to_seed();
            storage::save_seed(&dir, &seed);
            kp
        }
    };

    let peer = Peer::new(&keypair, name);
    let peer_id_hex = peer.id.to_hex();
    let short_code = peer.short_code.clone();

    let routing = RoutingTable::new(peer_id_hex.clone());
    *ROUTING_TABLE.lock().unwrap() = Some(routing);
    *OWN_PEER.lock().unwrap() = Some(peer);

    log::info!(
        "Identity ready: sc={} id={}...",
        short_code,
        &peer_id_hex[..peer_id_hex.len().min(16)]
    );
    JNI_TRUE
}

#[no_mangle]
pub extern "system" fn Java_com_example_meshlink_core_NativeCore_generateOwnPeer(
    mut env: JNIEnv,
    _class: JClass,
    device_name: JString,
) -> jboolean {
    save_vm!(env);
    let name = jstr!(env, device_name);
    let keypair = KeyPair::generate();
    let peer = Peer::new(&keypair, name);
    let peer_id_hex = peer.id.to_hex();
    let routing = RoutingTable::new(peer_id_hex.clone());
    *ROUTING_TABLE.lock().unwrap() = Some(routing);
    *OWN_PEER.lock().unwrap() = Some(peer);
    log::info!("Own peer generated (non-persistent)");
    JNI_TRUE
}


#[no_mangle]
pub extern "system" fn Java_com_example_meshlink_core_NativeCore_isInitialized(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    if OWN_PEER.lock().unwrap().is_some() {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_meshlink_core_NativeCore_getOwnShortCode(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let code = OWN_PEER
        .lock()
        .unwrap()
        .as_ref()
        .map(|p| p.short_code.clone())
        .unwrap_or_else(|| "----".to_string());
    new_jstring!(env, code)
}

#[no_mangle]
pub extern "system" fn Java_com_example_meshlink_core_NativeCore_getOwnPeerIdHex(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let hex = OWN_PEER
        .lock()
        .unwrap()
        .as_ref()
        .map(|p| p.id.to_hex())
        .unwrap_or_default();
    new_jstring!(env, hex)
}

#[no_mangle]
pub extern "system" fn Java_com_example_meshlink_core_NativeCore_getOwnPublicKeyHex(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let hex = OWN_PEER
        .lock()
        .unwrap()
        .as_ref()
        .map(|p| hex::encode(p.public_key_bytes))
        .unwrap_or_default();
    new_jstring!(env, hex)
}

#[no_mangle]
pub extern "system" fn Java_com_example_meshlink_core_NativeCore_getOwnName(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let name = OWN_PEER
        .lock()
        .unwrap()
        .as_ref()
        .map(|p| p.name.clone())
        .unwrap_or_default();
    new_jstring!(env, name)
}

#[no_mangle]
pub extern "system" fn Java_com_example_meshlink_core_NativeCore_getVersion(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    new_jstring!(env, env!("CARGO_PKG_VERSION"))
}


#[no_mangle]
pub extern "system" fn Java_com_example_meshlink_core_NativeCore_signData(
    mut env: JNIEnv,
    _class: JClass,
    data: JByteArray,
) -> jbyteArray {
    let bytes = match env.convert_byte_array(data) {
        Ok(b) => b,
        Err(e) => {
            log::error!("signData: convert_byte_array failed: {}", e);
            return env.new_byte_array(0).unwrap().into_raw();
        }
    };


    let signature: [u8; 64] = match OWN_PEER.lock().unwrap().as_ref() {
        Some(peer) => peer.sign(&bytes),
        None => {
            log::error!("signData: peer not initialized");
            return env.new_byte_array(0).unwrap().into_raw();
        }
    };

    match env.byte_array_from_slice(&signature) {
        Ok(arr) => arr.into_raw(),
        Err(_) => env.new_byte_array(0).unwrap().into_raw(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_meshlink_core_NativeCore_verifySignature(
    mut env: JNIEnv,
    _class: JClass,
    public_key_hex: JString,
    data: JByteArray,
    signature: JByteArray,
) -> jboolean {
    let pub_hex = jstr_ret!(env, public_key_hex, String::new());
    if pub_hex.len() != 64 {
        log::warn!("verifySignature: invalid pubkey hex len={}", pub_hex.len());
        return JNI_FALSE;
    }

    let pub_bytes_vec = match hex::decode(&pub_hex) {
        Ok(b) => b,
        Err(_) => return JNI_FALSE,
    };
    let mut pub_bytes = [0u8; 32];
    pub_bytes.copy_from_slice(&pub_bytes_vec);

    let data_bytes = match env.convert_byte_array(data) {
        Ok(b) => b,
        Err(_) => return JNI_FALSE,
    };

    let sig_bytes_vec = match env.convert_byte_array(signature) {
        Ok(b) => b,
        Err(_) => return JNI_FALSE,
    };
    if sig_bytes_vec.len() != 64 {
        return JNI_FALSE;
    }
    let mut sig_bytes = [0u8; 64];
    sig_bytes.copy_from_slice(&sig_bytes_vec);

    if KeyPair::verify_signature(&pub_bytes, &data_bytes, &sig_bytes) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}


#[no_mangle]
pub extern "system" fn Java_com_example_meshlink_core_NativeCore_updateRoutingTable(
    mut env: JNIEnv,
    _class: JClass,
    from_peer_id: JString,
    from_ip: JString,
    peers_json: JString,
) -> jint {
    let from_id = jstr_ret!(env, from_peer_id, String::new());
    let f_ip = jstr_ret!(env, from_ip, String::new());
    let json_str = jstr_ret!(env, peers_json, String::new());

    if from_id.is_empty() || f_ip.is_empty() {
        return 0;
    }

    let peers_val: Vec<serde_json::Value> = match serde_json::from_str(&json_str) {
        Ok(v) => v,
        Err(e) => {
            log::warn!("updateRoutingTable: invalid JSON: {}", e);
            return 0;
        }
    };

    let peer_tuples: Vec<(String, Option<String>, u8)> = peers_val
        .iter()
        .filter_map(|p| {
            let peer_id = p["peerId"].as_str()?.to_string();
            let ip = p["ip"].as_str().map(String::from);
            let hops = p["hops"].as_u64().unwrap_or(1) as u8;
            Some((peer_id, ip, hops))
        })
        .collect();

    let mut rt_lock = ROUTING_TABLE.lock().unwrap();
    if let Some(rt) = rt_lock.as_mut() {
        rt.learn_from_keepalive(&f_ip, &from_id, &peer_tuples);
        rt.size() as jint
    } else {
        0
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_meshlink_core_NativeCore_getNextHopIp(
    mut env: JNIEnv,
    _class: JClass,
    peer_id: JString,
) -> jstring {
    let id = jstr_ret!(env, peer_id, String::new());
    let ip = ROUTING_TABLE
        .lock()
        .unwrap()
        .as_ref()
        .and_then(|rt| rt.next_hop_ip(&id))
        .unwrap_or_default();
    new_jstring!(env, ip)
}

#[no_mangle]
pub extern "system" fn Java_com_example_meshlink_core_NativeCore_getRoutingTableJson(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let json = ROUTING_TABLE
        .lock()
        .unwrap()
        .as_ref()
        .map(|rt| {
            let routes = rt.all_routes();
            let arr: Vec<serde_json::Value> = routes
                .iter()
                .map(|(peer_id, ip, hops)| {
                    serde_json::json!({
                        "peerId": peer_id,
                        "ip": ip,
                        "hops": hops
                    })
                })
                .collect();
            serde_json::to_string(&arr).unwrap_or_else(|_| "[]".to_string())
        })
        .unwrap_or_else(|| "[]".to_string());
    new_jstring!(env, json)
}

#[no_mangle]
pub extern "system" fn Java_com_example_meshlink_core_NativeCore_getRouteCount(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    ROUTING_TABLE
        .lock()
        .unwrap()
        .as_ref()
        .map(|rt| rt.size() as jint)
        .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_example_meshlink_core_NativeCore_removeRoute(
    mut env: JNIEnv,
    _class: JClass,
    peer_id: JString,
) {
    let id = jstr_ret!(env, peer_id, String::new());
    if let Some(rt) = ROUTING_TABLE.lock().unwrap().as_mut() {
        rt.remove(&id);
    }
}


#[no_mangle]
pub extern "system" fn Java_com_example_meshlink_core_NativeCore_startServer(
    _env: JNIEnv,
    _class: JClass,
    port: jint,
) -> jboolean {
    match CONNECTION_MANAGER.lock().unwrap().start_server(port as u16) {
        Ok(_) => {
            log::info!("Server started on port {}", port);
            JNI_TRUE
        }
        Err(e) => {
            log::error!("Server start failed: {}", e);
            JNI_FALSE
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_meshlink_core_NativeCore_connectToPeer(
    mut env: JNIEnv,
    _class: JClass,
    peer_id: JString,
    address: JString,
) -> jboolean {
    let peer_id_str = jstr!(env, peer_id);
    let address_str = jstr!(env, address);

    let (our_id, our_name, our_code, our_pub_key) = {
        let lock = OWN_PEER.lock().unwrap();
        match lock.as_ref() {
            Some(p) => (
                p.id.to_hex(),
                p.name.clone(),
                p.short_code.clone(),
                p.public_key_bytes.to_vec(),
            ),
            None => {
                log::error!("connectToPeer: peer not initialized");
                return JNI_FALSE;
            }
        }
    };

    let handshake = Message::handshake(our_id, our_name, our_code, our_pub_key);
    match CONNECTION_MANAGER
        .lock()
        .unwrap()
        .connect_to_peer(&peer_id_str, &address_str, handshake)
    {
        Ok(_) => JNI_TRUE,
        Err(e) => {
            log::error!("connectToPeer failed: {}", e);
            JNI_FALSE
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_meshlink_core_NativeCore_sendTextMessage(
    mut env: JNIEnv,
    _class: JClass,
    peer_id: JString,
    content: JString,
) -> jboolean {
    let peer_id_str = jstr!(env, peer_id);
    let content_str = jstr!(env, content);

    let our_id = match OWN_PEER.lock().unwrap().as_ref() {
        Some(p) => p.id.to_hex(),
        None => return JNI_FALSE,
    };

    match CONNECTION_MANAGER
        .lock()
        .unwrap()
        .send_message(&peer_id_str, &content_str, &our_id)
    {
        Ok(_) => JNI_TRUE,
        Err(e) => {
            log::error!("sendTextMessage failed: {}", e);
            JNI_FALSE
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_meshlink_core_NativeCore_disconnect(
    mut env: JNIEnv,
    _class: JClass,
    peer_id: JString,
) {
    if let Ok(id) = env.get_string(&peer_id) {
        let id_str = String::from(id);
        CONNECTION_MANAGER.lock().unwrap().disconnect(&id_str);
        if let Some(rt) = ROUTING_TABLE.lock().unwrap().as_mut() {
            rt.remove(&id_str);
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_meshlink_core_NativeCore_shutdown(
    _env: JNIEnv,
    _class: JClass,
) {
    CONNECTION_MANAGER.lock().unwrap().shutdown();
    log::info!("ConnectionManager shut down");
}


#[no_mangle]
pub extern "system" fn Java_com_example_meshlink_core_NativeCore_setMessageCallback(
    mut env: JNIEnv,
    _class: JClass,
    callback_obj: JObject,
) {
    save_vm!(env);

    let global_ref = match env.new_global_ref(callback_obj) {
        Ok(r) => r,
        Err(e) => {
            log::error!("Global ref failed: {}", e);
            return;
        }
    };
    *CALLBACK_OBJ.lock().unwrap() = Some(global_ref);

    let callback: MessageCallback = Arc::new(move |peer_id: String, content: String| {
        let vm_lock = JAVA_VM.lock().unwrap();
        let cb_lock = CALLBACK_OBJ.lock().unwrap();
        if let (Some(vm), Some(cb_ref)) = (vm_lock.as_ref(), cb_lock.as_ref()) {
            match vm.attach_current_thread() {
                Ok(mut jni_env) => {
                    let j_peer = jni_env.new_string(&peer_id).unwrap();
                    let j_content = jni_env.new_string(&content).unwrap();
                    let _ = jni_env.call_method(
                        cb_ref,
                        "onMessageReceived",
                        "(Ljava/lang/String;Ljava/lang/String;)V",
                        &[(&j_peer).into(), (&j_content).into()],
                    );
                }
                Err(e) => log::error!("Attach thread failed: {}", e),
            }
        }
    });

    CONNECTION_MANAGER
        .lock()
        .unwrap()
        .set_message_callback(callback);
    log::info!("Message callback registered");
}

use jni::objects::{GlobalRef, JByteArray, JClass, JObject, JString};
use jni::sys::{jboolean, jbyteArray, jfloat, jint, jstring, JNI_FALSE, JNI_TRUE};
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
    let dir = jstr_ret!(env, files_dir, String::new());
    let name = jstr_ret!(env, device_name, String::new());

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

    log::info!("Identity ready: sc={} id={}...", short_code, &peer_id_hex[..peer_id_hex.len().min(16)]);
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
    if OWN_PEER.lock().unwrap().is_some() { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_example_meshlink_core_NativeCore_getOwnShortCode(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let code = OWN_PEER.lock().unwrap().as_ref()
        .map(|p| p.short_code.clone()).unwrap_or_else(|| "----".to_string());
    new_jstring!(env, code)
}

#[no_mangle]
pub extern "system" fn Java_com_example_meshlink_core_NativeCore_getOwnPeerIdHex(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let hex = OWN_PEER.lock().unwrap().as_ref()
        .map(|p| p.id.to_hex()).unwrap_or_default();
    new_jstring!(env, hex)
}

#[no_mangle]
pub extern "system" fn Java_com_example_meshlink_core_NativeCore_getOwnPublicKeyHex(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let hex = OWN_PEER.lock().unwrap().as_ref()
        .map(|p| hex::encode(p.public_key_bytes)).unwrap_or_default();
    new_jstring!(env, hex)
}

#[no_mangle]
pub extern "system" fn Java_com_example_meshlink_core_NativeCore_getOwnName(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let name = OWN_PEER.lock().unwrap().as_ref()
        .map(|p| p.name.clone()).unwrap_or_default();
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
    if pub_hex.len() != 64 { return JNI_FALSE; }
    let pub_bytes_vec = match hex::decode(&pub_hex) { Ok(b) => b, Err(_) => return JNI_FALSE };
    let mut pub_bytes = [0u8; 32]; pub_bytes.copy_from_slice(&pub_bytes_vec);
    let data_bytes = match env.convert_byte_array(data) { Ok(b) => b, Err(_) => return JNI_FALSE };
    let sig_bytes_vec = match env.convert_byte_array(signature) { Ok(b) => b, Err(_) => return JNI_FALSE };
    if sig_bytes_vec.len() != 64 { return JNI_FALSE; }
    let mut sig_bytes = [0u8; 64]; sig_bytes.copy_from_slice(&sig_bytes_vec);
    if KeyPair::verify_signature(&pub_bytes, &data_bytes, &sig_bytes) { JNI_TRUE } else { JNI_FALSE }
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
    if from_id.is_empty() || f_ip.is_empty() { return 0; }
    let peers_val: Vec<serde_json::Value> = match serde_json::from_str(&json_str) {
        Ok(v) => v,
        Err(e) => { log::warn!("updateRoutingTable: invalid JSON: {}", e); return 0; }
    };
    let peer_tuples: Vec<(String, Option<String>, u8)> = peers_val.iter()
        .filter_map(|p| {
            let peer_id = p["peerId"].as_str()?.to_string();
            let ip = p["ip"].as_str().map(String::from);
            let hops = p["hops"].as_u64().unwrap_or(1) as u8;
            Some((peer_id, ip, hops))
        }).collect();
    let mut rt_lock = ROUTING_TABLE.lock().unwrap();
    if let Some(rt) = rt_lock.as_mut() {
        rt.learn_from_keepalive(&f_ip, &from_id, &peer_tuples);
        rt.size() as jint
    } else { 0 }
}

#[no_mangle]
pub extern "system" fn Java_com_example_meshlink_core_NativeCore_getNextHopIp(
    mut env: JNIEnv,
    _class: JClass,
    peer_id: JString,
) -> jstring {
    let id = jstr_ret!(env, peer_id, String::new());
    let ip = ROUTING_TABLE.lock().unwrap().as_ref()
        .and_then(|rt| rt.next_hop_ip(&id)).unwrap_or_default();
    new_jstring!(env, ip)
}

#[no_mangle]
pub extern "system" fn Java_com_example_meshlink_core_NativeCore_getRoutingTableJson(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let json = ROUTING_TABLE.lock().unwrap().as_ref().map(|rt| {
        let routes = rt.all_routes();
        let arr: Vec<serde_json::Value> = routes.iter().map(|(peer_id, ip, hops)| {
            serde_json::json!({ "peerId": peer_id, "ip": ip, "hops": hops })
        }).collect();
        serde_json::to_string(&arr).unwrap_or_else(|_| "[]".to_string())
    }).unwrap_or_else(|| "[]".to_string());
    new_jstring!(env, json)
}

#[no_mangle]
pub extern "system" fn Java_com_example_meshlink_core_NativeCore_getRouteCount(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    ROUTING_TABLE.lock().unwrap().as_ref().map(|rt| rt.size() as jint).unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_example_meshlink_core_NativeCore_removeRoute(
    mut env: JNIEnv,
    _class: JClass,
    peer_id: JString,
) {
    let id = jstr_ret!(env, peer_id, String::new());
    if let Some(rt) = ROUTING_TABLE.lock().unwrap().as_mut() { rt.remove(&id); }
}

#[no_mangle]
pub extern "system" fn Java_com_example_meshlink_core_NativeCore_getRoutingStats(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let stats = ROUTING_TABLE.lock().unwrap().as_ref().map(|rt| rt.stats());
    let json = stats.map(|s| serde_json::json!({
        "total": s.total, "direct": s.direct, "mesh": s.mesh,
        "expired": s.expired, "suspect": s.suspect, "avg_rtt": s.avg_rtt
    }).to_string()).unwrap_or_else(|| "{}".to_string());
    new_jstring!(env, json)
}


#[no_mangle]
pub extern "system" fn Java_com_example_meshlink_core_NativeCore_startServer(
    _env: JNIEnv,
    _class: JClass,
    port: jint,
) -> jboolean {
    match CONNECTION_MANAGER.lock().unwrap().start_server(port as u16) {
        Ok(_) => { log::info!("Server started on port {}", port); JNI_TRUE }
        Err(e) => { log::error!("Server start failed: {}", e); JNI_FALSE }
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
            Some(p) => (p.id.to_hex(), p.name.clone(), p.short_code.clone(), p.public_key_bytes.to_vec()),
            None => { log::error!("connectToPeer: peer not initialized"); return JNI_FALSE; }
        }
    };
    let handshake = Message::handshake(our_id, our_name, our_code, our_pub_key);
    match CONNECTION_MANAGER.lock().unwrap().connect_to_peer(&peer_id_str, &address_str, handshake) {
        Ok(_) => JNI_TRUE,
        Err(e) => { log::error!("connectToPeer failed: {}", e); JNI_FALSE }
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
    match CONNECTION_MANAGER.lock().unwrap().send_message(&peer_id_str, &content_str, &our_id) {
        Ok(_) => JNI_TRUE,
        Err(e) => { log::error!("sendTextMessage failed: {}", e); JNI_FALSE }
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
        if let Some(rt) = ROUTING_TABLE.lock().unwrap().as_mut() { rt.remove(&id_str); }
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
        Err(e) => { log::error!("Global ref failed: {}", e); return; }
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
                    let _ = jni_env.call_method(cb_ref, "onMessageReceived",
                                                "(Ljava/lang/String;Ljava/lang/String;)V",
                                                &[(&j_peer).into(), (&j_content).into()]);
                }
                Err(e) => log::error!("Attach thread failed: {}", e),
            }
        }
    });
    CONNECTION_MANAGER.lock().unwrap().set_message_callback(callback);
    log::info!("Message callback registered");
}


use crate::protocol::file_transfer::{FileTransferManager, FileTransferMessage, TransferId};
static FILE_TRANSFER_MANAGER: Lazy<Mutex<FileTransferManager>> =
    Lazy::new(|| Mutex::new(FileTransferManager::new()));

#[no_mangle]
pub extern "system" fn Java_com_example_meshlink_core_NativeCore_startFileTransfer(
    mut env: JNIEnv,
    _class: JClass,
    peer_id: JString,
    filename: JString,
    file_data: JByteArray,
    chunk_size: jint,
) -> jstring {
    let peer_id_str = jstr_ret!(env, peer_id, String::new());
    let filename_str = jstr_ret!(env, filename, String::new());
    let file_bytes = match env.convert_byte_array(file_data) {
        Ok(b) => b,
        Err(e) => { log::error!("startFileTransfer: {}", e); return new_jstring!(env, ""); }
    };
    let chunk_size = if chunk_size > 0 { Some(chunk_size as usize) } else { None };
    let mut manager = FILE_TRANSFER_MANAGER.lock().unwrap();
    let transfer_id = manager.start_outgoing(peer_id_str, filename_str, file_bytes, chunk_size);
    new_jstring!(env, transfer_id.0)
}

#[no_mangle]
pub extern "system" fn Java_com_example_meshlink_core_NativeCore_handleFileTransferMessage(
    mut env: JNIEnv,
    _class: JClass,
    message_json: JString,
) -> jstring {
    let json_str = jstr_ret!(env, message_json, String::new());
    let msg = match FileTransferMessage::from_json(&json_str) {
        Ok(m) => m,
        Err(e) => { log::error!("handleFileTransferMessage: {}", e); return new_jstring!(env, "ERROR:parse"); }
    };
    let mut manager = FILE_TRANSFER_MANAGER.lock().unwrap();
    let response = match msg {
        FileTransferMessage::Init { ref transfer_id, .. } => {
            match manager.start_incoming(msg.clone()) {
                Ok(_) => format!("OK:INIT:{}", transfer_id.0),
                Err(e) => format!("ERROR:{}", e),
            }
        }
        FileTransferMessage::Chunk { transfer_id, chunk_index, data, chunk_hash, .. } => {
            if let Some(transfer) = manager.get_incoming_mut(&transfer_id) {
                match transfer.handle_chunk(chunk_index, data, chunk_hash) {
                    crate::protocol::file_transfer::ChunkHandleResult::ChunkReceived => {
                        if let Some(ack) = transfer.generate_ack(chunk_index).to_json().ok() {
                            format!("OK:ACK:{}", ack)
                        } else { "ERROR:ack_serialize".to_string() }
                    }
                    crate::protocol::file_transfer::ChunkHandleResult::TransferComplete => "OK:COMPLETE".to_string(),
                    crate::protocol::file_transfer::ChunkHandleResult::HashMismatch => format!("RETRY:HASH_MISMATCH:{}", chunk_index),
                    _ => "OK:IGNORED".to_string(),
                }
            } else { "ERROR:transfer_not_found".to_string() }
        }
        FileTransferMessage::ChunkAck { transfer_id, chunk_index, .. } => {
            if let Some(transfer) = manager.get_outgoing_mut(&transfer_id) {
                let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default().as_millis() as u64;
                match transfer.handle_ack(chunk_index, "", now) {
                    crate::protocol::file_transfer::AckResult::TransferComplete => {
                        manager.remove_completed(&transfer_id);
                        "OK:DONE".to_string()
                    }
                    _ => "OK:ACKED".to_string(),
                }
            } else { "ERROR:transfer_not_found".to_string() }
        }
        _ => "OK:UNHANDLED".to_string(),
    };
    new_jstring!(env, response)
}

#[no_mangle]
pub extern "system" fn Java_com_example_meshlink_core_NativeCore_getNextFileChunk(
    mut env: JNIEnv,
    _class: JClass,
    transfer_id: JString,
) -> jbyteArray {
    let tid_str = jstr_ret!(env, transfer_id, String::new());
    let transfer_id = TransferId(tid_str);
    let mut manager = FILE_TRANSFER_MANAGER.lock().unwrap();
    if let Some(transfer) = manager.get_outgoing_mut(&transfer_id) {
        let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default().as_millis() as u64;
        if let Some((chunk_idx, data, chunk_hash)) = transfer.next_chunk_to_send(now) {
            let chunk_msg = FileTransferMessage::Chunk {
                transfer_id: transfer.transfer_id.clone(),
                chunk_index: chunk_idx,
                total_chunks: transfer.total_chunks,
                data,
                chunk_hash,
            };
            if let Ok(bytes) = chunk_msg.to_bytes() {
                return match env.byte_array_from_slice(&bytes) {
                    Ok(arr) => arr.into_raw(),
                    Err(_) => env.new_byte_array(0).unwrap().into_raw(),
                };
            }
        }
    }
    env.new_byte_array(0).unwrap().into_raw()
}

#[no_mangle]
pub extern "system" fn Java_com_example_meshlink_core_NativeCore_getTransferProgress(
    mut env: JNIEnv,
    _class: JClass,
    transfer_id: JString,
) -> jfloat {
    let tid_str = jstr_ret!(env, transfer_id, String::new());
    let transfer_id = TransferId(tid_str);
    let manager = FILE_TRANSFER_MANAGER.lock().unwrap();
    let progress = manager.get_outgoing(&transfer_id)
        .map(|t| t.progress())
        .or_else(|| manager.get_incoming(&transfer_id).map(|t| t.progress()))
        .unwrap_or(0.0);
    progress as jfloat
}

#[no_mangle]
pub extern "system" fn Java_com_example_meshlink_core_NativeCore_cancelFileTransfer(
    mut _env: JNIEnv,
    _class: JClass,
    transfer_id: JString,
) {
    let tid_str = match _env.get_string(&transfer_id) { Ok(s) => String::from(s), Err(_) => return };
    let mut manager = FILE_TRANSFER_MANAGER.lock().unwrap();
    let transfer_id = TransferId(tid_str);
    manager.remove_completed(&transfer_id);
    log::debug!("File transfer cancelled: {}", transfer_id.0);
}


#[no_mangle]
pub extern "system" fn Java_com_example_meshlink_core_NativeCore_recordRouteSuccess(
    mut _env: JNIEnv,
    _class: JClass,
    peer_id: JString,
    rtt_ms: jint,
) {
    let id = match _env.get_string(&peer_id) { Ok(s) => String::from(s), Err(_) => return };
    if let Some(rt) = ROUTING_TABLE.lock().unwrap().as_mut() {
        rt.record_delivery_success(&id, rtt_ms as u32);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_meshlink_core_NativeCore_recordRouteFailure(
    mut _env: JNIEnv,
    _class: JClass,
    peer_id: JString,
) {
    let id = match _env.get_string(&peer_id) { Ok(s) => String::from(s), Err(_) => return };
    if let Some(rt) = ROUTING_TABLE.lock().unwrap().as_mut() {
        rt.record_delivery_failure(&id);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_meshlink_core_NativeCore_pruneRoutes(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    if let Some(rt) = ROUTING_TABLE.lock().unwrap().as_mut() {
        rt.prune() as jint
    } else { 0 }
}
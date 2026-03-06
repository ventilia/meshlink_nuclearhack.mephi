use std::collections::HashMap;
use std::io;
use std::net::{TcpListener, TcpStream};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

use crate::protocol::{Message, MessageType};
use crate::protocol::codec::{read_frame, write_frame};

pub type MessageCallback = Arc<dyn Fn(String, String) + Send + Sync>;

pub struct ConnectionManager {
    connections: Arc<Mutex<HashMap<String, TcpStream>>>,
    callback: Option<MessageCallback>,
    listener: Option<TcpListener>,
    is_running: Arc<Mutex<bool>>,
}

impl ConnectionManager {
    pub fn new() -> Self {
        Self {
            connections: Arc::new(Mutex::new(HashMap::new())),
            callback: None,
            listener: None,
            is_running: Arc::new(Mutex::new(false)),
        }
    }

    pub fn set_message_callback(&mut self, cb: MessageCallback) {
        self.callback = Some(cb);
    }

    pub fn start_server(&mut self, port: u16) -> io::Result<()> {
        let listener = TcpListener::bind(format!("0.0.0.0:{}", port))?;
        listener.set_nonblocking(true)?;
        *self.is_running.lock().unwrap() = true;

        let listener_clone = listener.try_clone()?;
        let connections = self.connections.clone();
        let callback = self.callback.clone();
        let is_running = self.is_running.clone();

        thread::spawn(move || {
            log::info!("TCP server listening on port {}", port);
            while *is_running.lock().unwrap() {
                match listener_clone.accept() {
                    Ok((stream, addr)) => {
                        log::info!("Incoming connection from {}", addr);
                        let conns = connections.clone();
                        let cb = callback.clone();
                        let addr_str = addr.to_string();
                        let stream_clone = stream.try_clone().expect("clone stream");
                        thread::spawn(move || {
                            Self::handle_stream(stream_clone, addr_str, conns, cb);
                        });
                    }
                    Err(ref e) if e.kind() == io::ErrorKind::WouldBlock => {
                        thread::sleep(Duration::from_millis(50));
                    }
                    Err(e) => {
                        log::error!("Accept error: {}", e);
                    }
                }
            }
        });

        self.listener = Some(listener);
        Ok(())
    }

    pub fn connect_to_peer(
        &self,
        peer_id: &str,
        address: &str,
        our_handshake: Message,
    ) -> Result<(), String> {
        let mut stream = TcpStream::connect(address)
            .map_err(|e| format!("Connect failed: {}", e))?;
        stream
            .set_read_timeout(Some(Duration::from_secs(10)))
            .map_err(|e| e.to_string())?;

        let json = our_handshake.to_json()?;
        write_frame(&mut stream, &json).map_err(|e| e.to_string())?;

        let ack_json = read_frame(&mut stream).map_err(|e| e.to_string())?;
        let _ack = Message::from_json(&ack_json)?;

        let stream_for_recv = stream.try_clone().map_err(|e| e.to_string())?;
        let peer_id_owned = peer_id.to_string();
        let cb = self.callback.clone();
        let conns_clone = self.connections.clone();

        thread::spawn(move || {
            Self::recv_loop(stream_for_recv, peer_id_owned, conns_clone, cb);
        });

        self.connections
            .lock()
            .unwrap()
            .insert(peer_id.to_string(), stream);

        Ok(())
    }

    pub fn send_message(
        &self,
        peer_id: &str,
        content: &str,
        our_id: &str,
    ) -> Result<(), String> {
        let msg = Message::text(our_id.to_string(), content.to_string());
        let json = msg.to_json()?;
        let mut conns = self.connections.lock().unwrap();
        if let Some(stream) = conns.get_mut(peer_id) {
            write_frame(stream, &json).map_err(|e| e.to_string())
        } else {
            Err(format!("Нет соединения с {}", peer_id))
        }
    }

    pub fn disconnect(&self, peer_id: &str) {
        if let Some(stream) = self.connections.lock().unwrap().remove(peer_id) {
            let _ = stream.shutdown(std::net::Shutdown::Both);
        }
    }

    pub fn shutdown(&mut self) {
        *self.is_running.lock().unwrap() = false;
        let peer_ids: Vec<String> = self
            .connections
            .lock()
            .unwrap()
            .keys()
            .cloned()
            .collect();
        for id in peer_ids {
            self.disconnect(&id);
        }
    }

    fn handle_stream(
        mut stream: TcpStream,
        addr: String,
        connections: Arc<Mutex<HashMap<String, TcpStream>>>,
        callback: Option<MessageCallback>,
    ) {
        let json = match read_frame(&mut stream) {
            Ok(j) => j,
            Err(e) => { log::error!("Failed to read handshake from {}: {}", addr, e); return; }
        };

        let msg = match Message::from_json(&json) {
            Ok(m) => m,
            Err(e) => { log::error!("Failed to parse handshake: {}", e); return; }
        };

        let peer_id = match &msg.msg_type {
            MessageType::Handshake { peer_id, .. } => peer_id.clone(),
            _ => { log::error!("Expected Handshake, got something else"); return; }
        };

        log::info!("Handshake from peer {}", peer_id);

        if let Ok(stream_clone) = stream.try_clone() {
            connections.lock().unwrap().insert(peer_id.clone(), stream_clone);
        }

        Self::recv_loop(stream, peer_id, connections, callback);
    }

    fn recv_loop(
        mut stream: TcpStream,
        peer_id: String,
        connections: Arc<Mutex<HashMap<String, TcpStream>>>,
        callback: Option<MessageCallback>,
    ) {
        loop {
            match read_frame(&mut stream) {
                Ok(json) => {
                    if let Ok(msg) = Message::from_json(&json) {
                        match &msg.msg_type {
                            MessageType::Text { content, .. } => {
                                if let Some(cb) = &callback {
                                    cb(peer_id.clone(), content.clone());
                                }
                            }
                            MessageType::Ping => {
                                let pong = Message::pong().to_json().unwrap_or_default();
                                let _ = write_frame(&mut stream, &pong);
                            }
                            _ => {
                                log::debug!("Unhandled message type from {}", peer_id);
                            }
                        }
                    }
                }
                Err(e) => {
                    log::warn!("Connection to {} closed: {}", peer_id, e);
                    connections.lock().unwrap().remove(&peer_id);
                    break;
                }
            }
        }
    }
}
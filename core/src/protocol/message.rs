use serde::{Deserialize, Serialize};


#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type")]


pub enum MessageType {

    Handshake {
        peer_id: String,
        name: String,
        short_code: String,
        public_key: Vec<u8>,
    },

    HandshakeAck {
        peer_id: String,
        public_key: Vec<u8>,
    },
    Text {
        id: String,
        sender_id: String,
        content: String,
        timestamp_ms: u64,
    },
    DeliveryAck {
        message_id: String,
    },
    FileOffer {
        transfer_id: String,
        sender_id: String,
        filename: String,
        size_bytes: u64,
        mime_type: String,
    },
    FileResponse {
        transfer_id: String,
        accepted: bool,
    },
    FileChunk {
        transfer_id: String,
        chunk_index: u32,
        total_chunks: u32,
        data: Vec<u8>,
    },
    CallRequest {
        call_id: String,
        caller_id: String,
        has_video: bool,
        sdp_offer: String,    
    },

    CallResponse {
        call_id: String,
        accepted: bool,
        sdp_answer: Option<String>,
    },

    CallEnd {
        call_id: String,
    },

    Ping,

    Pong,
    /// Для mesh-маршрутизации — будущее (Bitchat-like).
    RouteRequest {
        origin_id: String,
        target_id: String,
        hop_count: u8,
    },
    RouteReply {
        origin_id: String,
        target_id: String,
        path: Vec<String>,
    },
}


#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Message {
    pub msg_type: MessageType,
    pub timestamp: u64,
}

impl Message {
    pub fn new(msg_type: MessageType) -> Self {
        Self {
            msg_type,
            timestamp: unix_ms(),
        }
    }

    pub fn text(sender_id: impl Into<String>, content: impl Into<String>) -> Self {
        let sender_id = sender_id.into();
        let id = format!("{}-{}", sender_id, gen_id());
        Self::new(MessageType::Text {
            id,
            sender_id,
            content: content.into(),
            timestamp_ms: unix_ms(),
        })
    }

    pub fn handshake(
        peer_id: impl Into<String>,
        name: impl Into<String>,
        short_code: impl Into<String>,
        public_key: Vec<u8>,
    ) -> Self {
        Self::new(MessageType::Handshake {
            peer_id: peer_id.into(),
            name: name.into(),
            short_code: short_code.into(),
            public_key,
        })
    }

    pub fn ping() -> Self {
        Self::new(MessageType::Ping)
    }

    pub fn pong() -> Self {
        Self::new(MessageType::Pong)
    }

    pub fn to_json(&self) -> Result<String, String> {
        serde_json::to_string(self).map_err(|e| e.to_string())
    }

    pub fn from_json(s: &str) -> Result<Self, String> {
        serde_json::from_str(s).map_err(|e| e.to_string())
    }
}

fn unix_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::SystemTime::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

fn gen_id() -> String {
    use rand::Rng;
    format!("{:08x}", rand::thread_rng().gen::<u32>())
}



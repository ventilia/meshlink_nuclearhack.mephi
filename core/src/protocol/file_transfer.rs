// ==========================================
// ФАЙЛ: core/src/protocol/file_transfer.rs
// ОПИСАНИЕ: Протокол надёжной передачи файлов
// ==========================================
use serde::{Deserialize, Serialize};
use sha2::{Sha256, Digest};
use std::fmt;

pub const DEFAULT_CHUNK_SIZE: usize = 8 * 1024;
pub const MAX_RETRIES: u8 = 5;
pub const CHUNK_ACK_TIMEOUT_MS: u64 = 3000;

#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct TransferId(pub String);

impl TransferId {
    pub fn new(sender_id: &str, filename: &str, timestamp: u64) -> Self {
        use std::collections::hash_map::DefaultHasher;
        use std::hash::{Hash, Hasher};
        let mut hasher = DefaultHasher::new();
        sender_id.hash(&mut hasher);
        filename.hash(&mut hasher);
        timestamp.hash(&mut hasher);
        TransferId(format!("{:016x}", hasher.finish()))
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum TransferStatus {
    Initiated, InProgress, Completed, Failed, Cancelled,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", content = "payload")]
pub enum FileTransferMessage {
    Init {
        transfer_id: TransferId,
        filename: String,
        file_size: u64,
        file_hash: String,
        chunk_size: usize,
        mime_type: Option<String>,
        timestamp: u64,
    },
    Chunk {
        transfer_id: TransferId,
        chunk_index: u32,
        total_chunks: u32,
        data: Vec<u8>,
        chunk_hash: String,
    },
    ChunkAck {
        transfer_id: TransferId,
        chunk_index: u32,
        received_hash: String,
    },
    ChunkRetry {
        transfer_id: TransferId,
        chunk_index: u32,
        reason: RetryReason,
    },
    Complete {
        transfer_id: TransferId,
        status: TransferStatus,
        error: Option<String>,
    },
    StatusRequest {
        transfer_id: TransferId,
        last_received_chunk: Option<u32>,
    },
    StatusResponse {
        transfer_id: TransferId,
        total_chunks: u32,
        received_chunks: Vec<u32>,
        can_resume: bool,
    },
    Cancel {
        transfer_id: TransferId,
        reason: String,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum RetryReason {
    Timeout, HashMismatch, CorruptedData, ProtocolError,
}

impl fmt::Display for RetryReason {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            RetryReason::Timeout => write!(f, "timeout"),
            RetryReason::HashMismatch => write!(f, "hash_mismatch"),
            RetryReason::CorruptedData => write!(f, "corrupted_data"),
            RetryReason::ProtocolError => write!(f, "protocol_error"),
        }
    }
}

pub fn compute_sha256_hex(data: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(data);
    hex::encode(hasher.finalize())
}

pub fn chunk_data(data: &[u8], chunk_size: usize) -> Vec<(Vec<u8>, String)> {
    data.chunks(chunk_size)
        .map(|chunk| {
            let chunk_vec = chunk.to_vec();
            let hash = compute_sha256_hex(&chunk_vec);
            (chunk_vec, hash)
        })
        .collect()
}

#[derive(Debug, Clone)]
pub struct OutgoingTransfer {
    pub transfer_id: TransferId,
    pub peer_id: String,
    pub filename: String,
    pub total_chunks: u32,
    pub chunk_size: usize,
    pub file_hash: String,
    pub pending_chunks: std::collections::HashMap<u32, PendingChunk>,
    pub acknowledged_chunks: std::collections::HashSet<u32>,
    pub status: TransferStatus,
    pub started_at: u64,
    pub last_activity_at: u64,
}

#[derive(Debug, Clone)]
pub struct PendingChunk {
    pub data: Vec<u8>,
    pub chunk_hash: String,
    pub retry_count: u8,
    pub last_sent_at: u64,
    pub expected_ack_by: u64,
}

impl OutgoingTransfer {
    pub fn new(peer_id: String, filename: String, file_data: &[u8], chunk_size: usize) -> Self {
        let timestamp = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis() as u64;
        let transfer_id = TransferId::new(&peer_id, &filename, timestamp);
        let file_hash = compute_sha256_hex(file_data);
        let chunks = chunk_data(file_data, chunk_size);
        let total_chunks = chunks.len() as u32;
        let now = timestamp;
        let pending_chunks = chunks.into_iter().enumerate().map(|(idx, (data, hash))| {
            (idx as u32, PendingChunk {
                data, chunk_hash: hash, retry_count: 0,
                last_sent_at: 0, expected_ack_by: 0,
            })
        }).collect();
        Self {
            transfer_id, peer_id, filename, total_chunks, chunk_size, file_hash,
            pending_chunks,
            acknowledged_chunks: std::collections::HashSet::new(),
            status: TransferStatus::Initiated,
            started_at: now, last_activity_at: now,
        }
    }

    pub fn next_chunk_to_send(&mut self, now: u64) -> Option<(u32, Vec<u8>, String)> {
        for (&idx, chunk) in &mut self.pending_chunks {
            if !self.acknowledged_chunks.contains(&idx) {
                if chunk.retry_count == 0 || now >= chunk.expected_ack_by {
                    chunk.retry_count += 1;
                    chunk.last_sent_at = now;
                    chunk.expected_ack_by = now + CHUNK_ACK_TIMEOUT_MS;
                    self.last_activity_at = now;
                    return Some((idx, chunk.data.clone(), chunk.chunk_hash.clone()));
                }
            }
        }
        None
    }

    pub fn handle_ack(&mut self, chunk_index: u32, received_hash: &str, now: u64) -> AckResult {
        if let Some(chunk) = self.pending_chunks.get(&chunk_index) {
            if chunk.chunk_hash == received_hash {
                self.acknowledged_chunks.insert(chunk_index);
                self.pending_chunks.remove(&chunk_index);
                self.last_activity_at = now;
                if self.acknowledged_chunks.len() == self.total_chunks as usize {
                    self.status = TransferStatus::Completed;
                    AckResult::TransferComplete
                } else {
                    AckResult::ChunkAcked
                }
            } else {
                AckResult::HashMismatch
            }
        } else {
            AckResult::UnknownChunk
        }
    }

    pub fn check_timeouts(&mut self, now: u64) -> Vec<u32> {
        let mut retries = Vec::new();
        for (&idx, chunk) in &mut self.pending_chunks {
            if !self.acknowledged_chunks.contains(&idx)
                && now >= chunk.expected_ack_by
                && chunk.retry_count < MAX_RETRIES {
                chunk.retry_count += 1;
                chunk.last_sent_at = now;
                chunk.expected_ack_by = now + CHUNK_ACK_TIMEOUT_MS;
                retries.push(idx);
            } else if chunk.retry_count >= MAX_RETRIES {
                log::warn!("FileTransfer: MAX_RETRIES for chunk {} in {}", idx, self.transfer_id.0);
            }
        }
        retries
    }

    pub fn progress(&self) -> f32 {
        if self.total_chunks == 0 { return 1.0; }
        self.acknowledged_chunks.len() as f32 / self.total_chunks as f32
    }

    pub fn can_resume(&self) -> bool {
        self.status == TransferStatus::InProgress && !self.pending_chunks.is_empty()
    }
}

#[derive(Debug, PartialEq)]
pub enum AckResult { ChunkAcked, TransferComplete, HashMismatch, UnknownChunk }

#[derive(Debug, Clone)]
pub struct IncomingTransfer {
    pub transfer_id: TransferId,
    pub sender_id: String,
    pub filename: String,
    pub file_size: u64,
    pub expected_hash: String,
    pub chunk_size: usize,
    pub total_chunks: u32,
    pub received_chunks: std::collections::HashMap<u32, (Vec<u8>, String)>,
    pub status: TransferStatus,
    pub started_at: u64,
    pub last_activity_at: u64,
    pub assembled_data: Vec<u8>,
}

impl IncomingTransfer {
    pub fn new(sender_id: String, filename: String, file_size: u64, file_hash: String,
               chunk_size: usize, total_chunks: u32) -> Self {
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis() as u64;
        let mut assembled_data = Vec::with_capacity(file_size as usize);
        assembled_data.resize(file_size as usize, 0u8);
        Self {
            transfer_id: TransferId::new(&sender_id, &filename, now),
            sender_id, filename, file_size, expected_hash: file_hash,
            chunk_size, total_chunks,
            received_chunks: std::collections::HashMap::new(),
            status: TransferStatus::Initiated,
            started_at: now, last_activity_at: now,
            assembled_data,
        }
    }

    pub fn handle_chunk(&mut self, chunk_index: u32, data: Vec<u8>, chunk_hash: String) -> ChunkHandleResult {
        let actual_hash = compute_sha256_hex(&data);
        if actual_hash != chunk_hash {
            log::warn!("FileTransfer: Chunk {} hash mismatch!", chunk_index);
            return ChunkHandleResult::HashMismatch;
        }
        if self.received_chunks.contains_key(&chunk_index) {
            return ChunkHandleResult::Duplicate;
        }
        let start = (chunk_index as usize) * self.chunk_size;
        let end = (start + data.len()).min(self.file_size as usize);
        if start < self.assembled_data.len() {
            self.assembled_data[start..end].copy_from_slice(&data[..(end - start)]);
        }
        self.received_chunks.insert(chunk_index, (data, chunk_hash));
        self.last_activity_at = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis() as u64;
        if self.received_chunks.len() == self.total_chunks as usize {
            let actual_file_hash = compute_sha256_hex(&self.assembled_data);
            if actual_file_hash == self.expected_hash {
                self.status = TransferStatus::Completed;
                ChunkHandleResult::TransferComplete
            } else {
                log::error!("FileTransfer: Final hash mismatch!");
                self.status = TransferStatus::Failed;
                ChunkHandleResult::FileHashMismatch
            }
        } else {
            ChunkHandleResult::ChunkReceived
        }
    }

    pub fn generate_ack(&self, chunk_index: u32) -> FileTransferMessage {
        let received_hash = self.received_chunks
            .get(&chunk_index)
            .map(|(_, h)| h.clone())
            .unwrap_or_default();
        FileTransferMessage::ChunkAck {
            transfer_id: self.transfer_id.clone(),
            chunk_index, received_hash,
        }
    }

    pub fn missing_chunks(&self) -> Vec<u32> {
        (0..self.total_chunks).filter(|&i| !self.received_chunks.contains_key(&i)).collect()
    }

    pub fn progress(&self) -> f32 {
        if self.total_chunks == 0 { return 1.0; }
        self.received_chunks.len() as f32 / self.total_chunks as f32
    }

    pub fn finalize(self) -> Result<Vec<u8>, String> {
        if self.status != TransferStatus::Completed {
            return Err(format!("Transfer not completed: {:?}", self.status));
        }
        Ok(self.assembled_data)
    }
}

#[derive(Debug, PartialEq)]
pub enum ChunkHandleResult {
    ChunkReceived, TransferComplete, HashMismatch, FileHashMismatch, Duplicate,
}

#[derive(Default)]
pub struct FileTransferManager {
    outgoing: std::collections::HashMap<TransferId, OutgoingTransfer>,
    incoming: std::collections::HashMap<TransferId, IncomingTransfer>,
}

impl FileTransferManager {
    pub fn new() -> Self {
        Self { outgoing: std::collections::HashMap::new(), incoming: std::collections::HashMap::new() }
    }

    pub fn start_outgoing(&mut self, peer_id: String, filename: String, file_data: Vec<u8>,
                          chunk_size: Option<usize>) -> TransferId {
        let chunk_size = chunk_size.unwrap_or(DEFAULT_CHUNK_SIZE);
        let transfer = OutgoingTransfer::new(peer_id, filename, &file_data, chunk_size);
        let transfer_id = transfer.transfer_id.clone();
        self.outgoing.insert(transfer_id.clone(), transfer);
        transfer_id
    }

    pub fn start_incoming(&mut self, init_msg: FileTransferMessage) -> Result<TransferId, String> {
        if let FileTransferMessage::Init { transfer_id, filename, file_size, file_hash, chunk_size, .. } = init_msg {
            let total_chunks = ((file_size as usize) + chunk_size - 1) / chunk_size;
            let transfer = IncomingTransfer::new(String::new(), filename, file_size, file_hash, chunk_size, total_chunks as u32);
            let tid = transfer.transfer_id.clone();
            self.incoming.insert(tid.clone(), transfer);
            Ok(tid)
        } else {
            Err("Expected Init message".to_string())
        }
    }

    pub fn get_outgoing(&self, transfer_id: &TransferId) -> Option<&OutgoingTransfer> { self.outgoing.get(transfer_id) }
    pub fn get_outgoing_mut(&mut self, transfer_id: &TransferId) -> Option<&mut OutgoingTransfer> { self.outgoing.get_mut(transfer_id) }
    pub fn get_incoming(&self, transfer_id: &TransferId) -> Option<&IncomingTransfer> { self.incoming.get(transfer_id) }
    pub fn get_incoming_mut(&mut self, transfer_id: &TransferId) -> Option<&mut IncomingTransfer> { self.incoming.get_mut(transfer_id) }
    pub fn remove_completed(&mut self, transfer_id: &TransferId) {
        self.outgoing.remove(transfer_id); self.incoming.remove(transfer_id);
    }

    pub fn active_transfers(&self) -> Vec<TransferInfo> {
        let mut result = Vec::new();
        for t in self.outgoing.values() {
            result.push(TransferInfo {
                transfer_id: t.transfer_id.clone(), direction: TransferDirection::Outgoing,
                peer_id: t.peer_id.clone(), filename: t.filename.clone(),
                progress: t.progress(), status: t.status,
            });
        }
        for t in self.incoming.values() {
            result.push(TransferInfo {
                transfer_id: t.transfer_id.clone(), direction: TransferDirection::Incoming,
                peer_id: t.sender_id.clone(), filename: t.filename.clone(),
                progress: t.progress(), status: t.status,
            });
        }
        result
    }

    pub fn prune_inactive(&mut self, timeout_secs: u64) -> usize {
        let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default().as_millis() as u64;
        let timeout_ms = timeout_secs * 1000;
        let mut removed = 0;
        self.outgoing.retain(|_, t| {
            let keep = now - t.last_activity_at < timeout_ms && t.status == TransferStatus::InProgress;
            if !keep { removed += 1; } keep
        });
        self.incoming.retain(|_, t| {
            let keep = now - t.last_activity_at < timeout_ms && t.status == TransferStatus::InProgress;
            if !keep { removed += 1; } keep
        });
        if removed > 0 { log::debug!("FileTransferManager: pruned {} inactive", removed); }
        removed
    }
}

#[derive(Debug, Clone)]
pub struct TransferInfo {
    pub transfer_id: TransferId, pub direction: TransferDirection,
    pub peer_id: String, pub filename: String,
    pub progress: f32, pub status: TransferStatus,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum TransferDirection { Incoming, Outgoing }

impl FileTransferMessage {
    pub fn to_json(&self) -> Result<String, serde_json::Error> { serde_json::to_string(self) }
    pub fn from_json(s: &str) -> Result<Self, serde_json::Error> { serde_json::from_str(s) }
    pub fn to_bytes(&self) -> Result<Vec<u8>, serde_json::Error> { serde_json::to_vec(self) }
    pub fn from_bytes(data: &[u8]) -> Result<Self, serde_json::Error> { serde_json::from_slice(data) }
}
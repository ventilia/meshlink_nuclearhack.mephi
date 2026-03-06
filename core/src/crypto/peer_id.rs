use sha2::{Digest, Sha256};

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct PeerId(pub [u8; 32]);

impl PeerId {
    pub fn from_public_key_bytes(pub_key: &[u8; 32]) -> Self {
        let hash = Sha256::digest(pub_key);
        let mut arr = [0u8; 32];
        arr.copy_from_slice(&hash);
        PeerId(arr)
    }

    pub fn short_code(&self) -> String {
        const ALPHABET: &[u8] = b"ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        (0..4)
            .map(|i| ALPHABET[self.0[i] as usize % ALPHABET.len()] as char)
            .collect()
    }

    pub fn to_hex(&self) -> String {
        hex::encode(self.0)
    }

    pub fn from_hex(s: &str) -> Result<Self, String> {
        let bytes = hex::decode(s).map_err(|e| e.to_string())?;
        if bytes.len() != 32 {
            return Err("PeerId: ожидается 32 байта".into());
        }
        let mut arr = [0u8; 32];
        arr.copy_from_slice(&bytes);
        Ok(PeerId(arr))
    }
}
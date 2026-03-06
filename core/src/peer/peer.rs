
use crate::crypto::KeyPair;
use crate::crypto::PeerId;

#[derive(Debug, Clone)]
pub struct Peer {
    pub id: PeerId,
    pub name: String,
    pub short_code: String,
    pub public_key_bytes: [u8; 32],
    keypair: KeyPair,
}

impl Peer {
    pub fn new(keypair: &KeyPair, name: String) -> Self {
        let id = PeerId::from_public_key_bytes(&keypair.public_key_bytes);
        let short_code = id.short_code();
        Self {
            id,
            name,
            short_code,
            public_key_bytes: keypair.public_key_bytes,
            keypair: keypair.clone(),
        }
    }


    pub fn sign(&self, data: &[u8]) -> [u8; 64] {
        self.keypair.sign(data)
    }
}


use ed25519_dalek::{SigningKey, Signature, Signer, Verifier, VerifyingKey};
use rand::rngs::OsRng;

#[derive(Clone, Debug)]
pub struct KeyPair {
    pub public_key_bytes: [u8; 32],
    signing_key: SigningKey,
}

impl KeyPair {
    pub fn generate() -> Self {
        let signing_key = SigningKey::generate(&mut OsRng);
        let public_key_bytes = signing_key.verifying_key().to_bytes();
        Self { public_key_bytes, signing_key }
    }


    pub fn from_seed(seed: &[u8; 32]) -> Self {
        let signing_key = SigningKey::from_bytes(seed);
        let public_key_bytes = signing_key.verifying_key().to_bytes();
        Self { public_key_bytes, signing_key }
    }

    pub fn to_seed(&self) -> [u8; 32] {
        self.signing_key.to_bytes()
    }

    pub fn sign(&self, data: &[u8]) -> [u8; 64] {
        self.signing_key.sign(data).to_bytes()
    }

    pub fn verify_signature(
        public_key_bytes: &[u8; 32],
        data: &[u8],
        signature_bytes: &[u8; 64],
    ) -> bool {
        let Ok(vk) = VerifyingKey::from_bytes(public_key_bytes) else {
            return false;
        };
        let sig = Signature::from_bytes(signature_bytes);
        vk.verify(data, &sig).is_ok()
    }
}

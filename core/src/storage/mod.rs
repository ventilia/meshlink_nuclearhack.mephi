

use std::fs;
use std::io;
use std::path::Path;

const IDENTITY_FILE: &str = "meshlink_identity.bin";
const SEED_LEN: usize = 32;

pub fn load_seed(files_dir: &str) -> Option<[u8; SEED_LEN]> {
    let path = format!("{}/{}", files_dir, IDENTITY_FILE);
    match fs::read(&path) {
        Ok(bytes) if bytes.len() == SEED_LEN => {
            let mut arr = [0u8; SEED_LEN];
            arr.copy_from_slice(&bytes);
            log::info!("Identity loaded from {}", path);
            Some(arr)
        }
        Ok(bytes) => {
            log::warn!("Identity file corrupt (len={}), regenerating", bytes.len());
            None
        }
        Err(e) if e.kind() == io::ErrorKind::NotFound => {
            log::info!("No identity file found, will generate new");
            None
        }
        Err(e) => {
            log::error!("Failed to read identity: {}", e);
            None
        }
    }
}

pub fn save_seed(files_dir: &str, seed: &[u8; SEED_LEN]) -> bool {
    let path = format!("{}/{}", files_dir, IDENTITY_FILE);
    match fs::write(&path, seed) {
        Ok(_) => {
            log::info!("Identity saved to {}", path);
            true
        }
        Err(e) => {
            log::error!("Failed to save identity: {}", e);
            false
        }
    }
}

pub fn identity_exists(files_dir: &str) -> bool {
    Path::new(&format!("{}/{}", files_dir, IDENTITY_FILE)).exists()
}

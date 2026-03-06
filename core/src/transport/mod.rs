use std::io;


pub type TransportPeerId = String;

pub trait Transport: Send + Sync {

    fn name(&self) -> &'static str;


    fn start(&mut self) -> io::Result<()>;
    
    fn connect(&self, address: &str) -> io::Result<Box<dyn PeerStream>>;

    fn stop(&mut self);
}


pub trait PeerStream: Send + Sync {
    fn send(&mut self, data: &[u8]) -> io::Result<()>;
    fn recv(&mut self) -> io::Result<Vec<u8>>;
    fn peer_address(&self) -> &str;
    fn close(&mut self);
}
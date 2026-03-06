use std::io;
use std::net::TcpStream;
use std::time::Duration;

use crate::protocol::codec::{read_frame, write_frame};
use crate::transport::PeerStream;

pub struct TcpPeerStream {
    stream: TcpStream,
    address: String,
}

impl TcpPeerStream {
    pub fn connect(address: &str) -> io::Result<Self> {
        let stream = TcpStream::connect(address)?;
        stream.set_read_timeout(Some(Duration::from_secs(30)))?;
        stream.set_write_timeout(Some(Duration::from_secs(10)))?;
        Ok(Self { stream, address: address.to_string() })
    }

    pub fn from_stream(stream: TcpStream, address: String) -> Self {
        Self { stream, address }
    }
}

impl PeerStream for TcpPeerStream {
    fn send(&mut self, data: &[u8]) -> io::Result<()> {
        // data — уже JSON строка как байты
        let json = std::str::from_utf8(data)
            .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
        write_frame(&mut self.stream, json)
    }

    fn recv(&mut self) -> io::Result<Vec<u8>> {
        let frame = read_frame(&mut self.stream)?;
        Ok(frame.into_bytes())
    }

    fn peer_address(&self) -> &str {
        &self.address
    }

    fn close(&mut self) {
        let _ = self.stream.shutdown(std::net::Shutdown::Both);
    }
}
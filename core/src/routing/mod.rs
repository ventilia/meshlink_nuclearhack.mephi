use std::collections::HashMap;
use std::time::{Duration, SystemTime};

pub const MAX_HOPS: u8 = 5;
const ROUTE_TIMEOUT_SECS: u64 = 60;


#[derive(Debug, Clone)]
pub struct Route {

    pub next_hop_ip: String,

    pub hop_count: u8,

    pub via_peer_id: Option<String>,

    pub updated_at: SystemTime,
}

impl Route {
    pub fn direct(ip: String) -> Self {
        Self {
            next_hop_ip: ip,
            hop_count: 1,
            via_peer_id: None,
            updated_at: SystemTime::now(),
        }
    }

    pub fn via(next_hop_ip: String, hop_count: u8, via_peer_id: String) -> Self {
        Self {
            next_hop_ip,
            hop_count,
            via_peer_id: Some(via_peer_id),
            updated_at: SystemTime::now(),
        }
    }

    pub fn is_expired(&self) -> bool {
        self.updated_at
            .elapsed()
            .map(|e| e > Duration::from_secs(ROUTE_TIMEOUT_SECS))
            .unwrap_or(true)
    }
}


pub struct RoutingTable {
    routes: HashMap<String, Route>,
    own_peer_id: String,
}

impl RoutingTable {
    pub fn new(own_peer_id: String) -> Self {
        Self {
            routes: HashMap::new(),
            own_peer_id,
        }
    }

    pub fn update(&mut self, peer_id: &str, route: Route) -> bool {
        if peer_id == self.own_peer_id || peer_id.is_empty() {
            return false;
        }
        let should_update = match self.routes.get(peer_id) {
            None => true,
            Some(existing) => {
                existing.is_expired() || route.hop_count < existing.hop_count
            }
        };
        if should_update {
            log::debug!(
                "Route update: {} → {} (hops={}, via={:?})",
                &peer_id[..peer_id.len().min(8)],
                route.next_hop_ip,
                route.hop_count,
                route.via_peer_id
            );
            self.routes.insert(peer_id.to_string(), route);
        }
        should_update
    }

    pub fn get(&self, peer_id: &str) -> Option<&Route> {
        self.routes.get(peer_id).filter(|r| !r.is_expired())
    }

    pub fn next_hop_ip(&self, peer_id: &str) -> Option<String> {
        self.get(peer_id).map(|r| r.next_hop_ip.clone())
    }


    pub fn learn_from_keepalive(
        &mut self,
        from_ip: &str,
        from_peer_id: &str,
        peers: &[(String, Option<String>, u8)], // (peerId, ip, hopCount)
    ) {
        self.update(
            from_peer_id,
            Route::direct(from_ip.to_string()),
        );

        for (peer_id, _peer_ip, hop_count) in peers {
            if peer_id == &self.own_peer_id || peer_id == from_peer_id {
                continue;
            }
            if *hop_count >= MAX_HOPS {
                continue;
            }
            let route = Route::via(
                from_ip.to_string(),
                hop_count + 1,
                from_peer_id.to_string(),
            );
            self.update(peer_id, route);
        }
    }

    pub fn prune(&mut self) -> usize {
        let before = self.routes.len();
        self.routes.retain(|_, r| !r.is_expired());
        let removed = before - self.routes.len();
        if removed > 0 {
            log::info!("RoutingTable: pruned {} stale routes", removed);
        }
        removed
    }

    pub fn all_routes(&self) -> Vec<(String, String, u8)> {
        self.routes
            .iter()
            .filter(|(_, r)| !r.is_expired())
            .map(|(peer_id, r)| (peer_id.clone(), r.next_hop_ip.clone(), r.hop_count))
            .collect()
    }

    pub fn size(&self) -> usize {
        self.routes.len()
    }

    pub fn remove(&mut self, peer_id: &str) {
        self.routes.remove(peer_id);
    }
}

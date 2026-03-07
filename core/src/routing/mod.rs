use std::collections::HashMap;
use std::time::{Duration, SystemTime};
use std::sync::atomic::{AtomicU64, Ordering};

pub const MAX_HOPS: u8 = 4;
const ROUTE_TIMEOUT_SECS: u64 = 60;
const ROUTE_FAILURE_THRESHOLD: u32 = 3;

#[derive(Debug, Clone, Copy, Default)]
pub struct RouteMetrics {
    pub avg_rtt_ms: u32,
    pub packet_loss_percent: u8,
    pub consecutive_failures: u32,
    pub success_count: u64,
}

impl RouteMetrics {
    pub fn record_success(&mut self, rtt_ms: u32) {
        if self.avg_rtt_ms == 0 {
            self.avg_rtt_ms = rtt_ms;
        } else {
            self.avg_rtt_ms = (self.avg_rtt_ms * 7 + rtt_ms) / 8;
        }
        self.consecutive_failures = 0;
        self.success_count = self.success_count.saturating_add(1);
        if self.packet_loss_percent > 0 {
            self.packet_loss_percent -= 1;
        }
    }

    pub fn record_failure(&mut self) {
        self.consecutive_failures = self.consecutive_failures.saturating_add(1);
        self.packet_loss_percent = (self.packet_loss_percent.saturating_add(5)).min(100);
    }

    pub fn cost(&self, hop_count: u8) -> u32 {
        let hop_cost = (hop_count as u32) * 100;
        let rtt_cost = self.avg_rtt_ms / 10;
        let loss_cost = (self.packet_loss_percent as u32) * 2;
        let fail_cost = self.consecutive_failures * 50;
        hop_cost.saturating_add(rtt_cost).saturating_add(loss_cost).saturating_add(fail_cost)
    }

    pub fn is_usable(&self) -> bool {
        self.consecutive_failures < ROUTE_FAILURE_THRESHOLD
    }
}

#[derive(Debug, Clone)]
pub struct Route {
    pub next_hop_ip: String,
    pub hop_count: u8,
    pub via_peer_id: Option<String>,
    pub updated_at: SystemTime,
    pub metrics: RouteMetrics,
    pub last_success_at: Option<SystemTime>,
}

impl Route {
    pub fn direct(ip: String) -> Self {
        Self {
            next_hop_ip: ip,
            hop_count: 1,
            via_peer_id: None,
            updated_at: SystemTime::now(),
            metrics: RouteMetrics::default(),
            last_success_at: None,
        }
    }

    pub fn via(next_hop_ip: String, hop_count: u8, via_peer_id: String) -> Self {
        Self {
            next_hop_ip,
            hop_count,
            via_peer_id: Some(via_peer_id),
            updated_at: SystemTime::now(),
            metrics: RouteMetrics::default(),
            last_success_at: None,
        }
    }

    pub fn is_expired(&self) -> bool {
        self.updated_at
            .elapsed()
            .map(|e| e > Duration::from_secs(ROUTE_TIMEOUT_SECS))
            .unwrap_or(true)
    }

    pub fn mark_success(&mut self, rtt_ms: u32) {
        self.metrics.record_success(rtt_ms);
        self.last_success_at = Some(SystemTime::now());
    }

    pub fn mark_failure(&mut self) {
        self.metrics.record_failure();
    }
}

pub struct RoutingTable {
    routes: HashMap<String, Route>,
    own_peer_id: String,
    log_counter: AtomicU64,
}

impl RoutingTable {
    pub fn new(own_peer_id: String) -> Self {
        log::info!(
            "RoutingTable initialized | own_peer_id={:.16}...",
            own_peer_id
        );
        Self {
            routes: HashMap::with_capacity(32),
            own_peer_id,
            log_counter: AtomicU64::new(0),
        }
    }

    pub fn update(&mut self, peer_id: &str, route: Route) -> bool {
        if peer_id.is_empty() {
            log::warn!("RoutingTable::update: empty peer_id");
            return false;
        }
        if !self.own_peer_id.is_empty() && peer_id == self.own_peer_id {
            log::debug!(
                "RoutingTable: REJECTED self-route for peer_id={:.8}...",
                peer_id
            );
            return false;
        }
        if route.hop_count == 0 || route.hop_count > MAX_HOPS {
            log::debug!(
                "RoutingTable: REJECTED invalid hop_count={} for peer={:.8}...",
                route.hop_count, peer_id
            );
            return false;
        }

        let should_update = match self.routes.get(peer_id) {
            None => {
                log::debug!(
                    "RoutingTable: NEW route | peer={:.8} → {} (hops={}, via={})",
                    peer_id,
                    route.next_hop_ip,
                    route.hop_count,
                    route.via_peer_id.as_deref().unwrap_or("direct")
                );
                true
            }
            Some(existing) => {
                let existing_cost = existing.metrics.cost(existing.hop_count);
                let new_cost = route.metrics.cost(route.hop_count);

                let reason = if existing.is_expired() {
                    Some("expired")
                } else if new_cost < existing_cost {
                    Some("better_cost")
                } else if route.hop_count < existing.hop_count {
                    Some("shorter_path")
                } else if route.hop_count == existing.hop_count
                    && route.via_peer_id.is_none()
                    && existing.via_peer_id.is_some() {
                    Some("direct_over_mesh")
                } else {
                    None
                };

                if let Some(r) = reason {
                    log::debug!(
                        "RoutingTable: UPDATE route | peer={:.8} | reason={} | cost:{}→{}",
                        peer_id, r, existing_cost, new_cost
                    );
                    true
                } else {
                    if log::log_enabled!(log::Level::Trace) {
                        log::trace!(
                            "RoutingTable: SKIP update | peer={:.8} | existing_cost={} <= new_cost={}",
                            peer_id, existing_cost, new_cost
                        );
                    }
                    false
                }
            }
        };

        if should_update {
            self.routes.insert(peer_id.to_string(), route);
        }
        should_update
    }

    pub fn get(&self, peer_id: &str) -> Option<&Route> {
        self.routes.get(peer_id).filter(|r| !r.is_expired() && r.metrics.is_usable())
    }

    pub fn get_mut(&mut self, peer_id: &str) -> Option<&mut Route> {
        self.routes.get_mut(peer_id).filter(|r| !r.is_expired())
    }

    pub fn next_hop_ip(&self, peer_id: &str) -> Option<String> {
        self.get(peer_id).map(|r| r.next_hop_ip.clone())
    }

    pub fn record_delivery_success(&mut self, peer_id: &str, rtt_ms: u32) {
        if let Some(route) = self.get_mut(peer_id) {
            route.mark_success(rtt_ms);
            if log::log_enabled!(log::Level::Debug) {
                log::debug!(
                    "RoutingTable: SUCCESS | peer={:.8} | rtt={}ms | loss={}%",
                    peer_id, rtt_ms, route.metrics.packet_loss_percent
                );
            }
        }
    }

    pub fn record_delivery_failure(&mut self, peer_id: &str) {
        if let Some(route) = self.get_mut(peer_id) {
            route.mark_failure();
            log::warn!(
                "RoutingTable: FAILURE | peer={:.8} | failures={}/{}",
                peer_id,
                route.metrics.consecutive_failures,
                ROUTE_FAILURE_THRESHOLD
            );
        }
    }

    pub fn learn_from_keepalive(
        &mut self,
        from_ip: &str,
        from_peer_id: &str,
        peers: &[(String, Option<String>, u8)],
    ) {
        let log_id = self.log_counter.fetch_add(1, Ordering::Relaxed);

        if from_peer_id.is_empty() || from_peer_id == self.own_peer_id {
            log::debug!(
                "RoutingTable[{}]: SKIP keepalive from invalid source: peer_id={:.8}",
                log_id, from_peer_id
            );
            return;
        }

        if self.update(from_peer_id, Route::direct(from_ip.to_string())) {
            log::debug!(
                "RoutingTable[{}]: DIRECT route added | peer={:.8} → {}",
                log_id, from_peer_id, from_ip
            );
        }


        let own_id = self.own_peer_id.clone();

        for (peer_id, _peer_ip, hop_count) in peers {
            if peer_id.is_empty() { continue; }

            if peer_id == &own_id {
                log::trace!("RoutingTable[{}]: FILTERED own peer_id", log_id);
                continue;
            }
            if peer_id == from_peer_id { continue; }
            if *hop_count == 0 {
                log::trace!("RoutingTable[{}]: FILTERED hop_count=0", log_id);
                continue;
            }
            if *hop_count >= MAX_HOPS {
                log::trace!("RoutingTable[{}]: FILTERED excessive hops={}", log_id, hop_count);
                continue;
            }

            let new_route = Route::via(
                from_ip.to_string(),
                hop_count.saturating_add(1),
                from_peer_id.to_string(),
            );


            if self.update(peer_id, new_route) {
                log::debug!(
                    "RoutingTable[{}]: MESH route learned | peer={:.8} → via={:.8} (hops={})",
                    log_id, peer_id, from_peer_id, hop_count.saturating_add(1)
                );
            }
        }
    }

    pub fn prune(&mut self) -> usize {
        let before = self.routes.len();
        self.routes.retain(|peer_id, route| {
            if route.is_expired() {
                log::debug!(
                    "RoutingTable: PRUNED expired route | peer={:.8}",
                    peer_id
                );
                false
            } else if !route.metrics.is_usable() {
                log::debug!(
                    "RoutingTable: PRUNED failed route | peer={:.8} | failures={}",
                    peer_id,
                    route.metrics.consecutive_failures
                );
                false
            } else {
                true
            }
        });
        let removed = before - self.routes.len();
        if removed > 0 {
            log::info!("RoutingTable: pruned {} routes | remaining={}", removed, self.routes.len());
        }
        removed
    }

    pub fn all_routes(&self) -> Vec<(String, String, u8)> {
        self.routes
            .iter()
            .filter(|(_, r)| !r.is_expired() && r.metrics.is_usable())
            .map(|(peer_id, r)| {
                (peer_id.clone(), r.next_hop_ip.clone(), r.hop_count)
            })
            .collect()
    }

    pub fn stats(&self) -> RoutingStats {
        let mut direct = 0u32;
        let mut mesh = 0u32;
        let mut expired = 0u32;
        let mut suspect = 0u32;

        for (_, route) in &self.routes {
            if route.is_expired() {
                expired += 1;
            } else if !route.metrics.is_usable() {
                suspect += 1;
            } else if route.via_peer_id.is_none() {
                direct += 1;
            } else {
                mesh += 1;
            }
        }

        RoutingStats {
            total: self.routes.len() as u32,
            direct,
            mesh,
            expired,
            suspect,
            avg_rtt: if direct + mesh > 0 {
                self.routes.values()
                    .filter(|r| !r.is_expired() && r.metrics.is_usable())
                    .map(|r| r.metrics.avg_rtt_ms)
                    .filter(|&rtt| rtt > 0)
                    .sum::<u32>() / (direct + mesh)
            } else { 0 },
        }
    }

    pub fn size(&self) -> usize {
        self.routes.len()
    }

    pub fn remove(&mut self, peer_id: &str) {
        if self.routes.remove(peer_id).is_some() {
            log::debug!("RoutingTable: REMOVED route | peer={:.8}", peer_id);
        }
    }

    pub fn set_own_peer_id(&mut self, own_peer_id: String) {
        if self.own_peer_id != own_peer_id {
            log::info!(
                "RoutingTable: own_peer_id changed | old={:.16}... → new={:.16}...",
                self.own_peer_id, own_peer_id
            );
            self.routes.remove(&self.own_peer_id);
            self.own_peer_id = own_peer_id;
        }
    }

    pub fn own_peer_id(&self) -> &str {
        &self.own_peer_id
    }
}

#[derive(Debug, Clone, Copy)]
pub struct RoutingStats {
    pub total: u32,
    pub direct: u32,
    pub mesh: u32,
    pub expired: u32,
    pub suspect: u32,
    pub avg_rtt: u32,
}

impl std::fmt::Display for RoutingStats {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "RoutingStats[total={}, direct={}, mesh={}, expired={}, suspect={}, avg_rtt={}ms]",
            self.total, self.direct, self.mesh, self.expired, self.suspect, self.avg_rtt
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_no_self_relay() {
        let own_id = "aabbccdd11223344".to_string();
        let mut rt = RoutingTable::new(own_id.clone());
        let peers = vec![
            (own_id.clone(), Some("192.168.1.1".to_string()), 1u8),
            ("peer2222".to_string(), Some("192.168.1.2".to_string()), 1u8),
        ];
        rt.learn_from_keepalive("192.168.1.5", "peer1111", &peers);
        assert!(rt.get(&own_id).is_none());
        assert!(rt.get("peer1111").is_some());
        assert!(rt.get("peer2222").is_some());
    }

    #[test]
    fn test_no_zero_hop_route() {
        let own_id = "aabbccdd11223344".to_string();
        let mut rt = RoutingTable::new(own_id.clone());
        let peers = vec![("somepeer".to_string(), None, 0u8)];
        rt.learn_from_keepalive("192.168.1.5", "peer1111", &peers);
        assert!(rt.get("somepeer").is_none());
    }

    #[test]
    fn test_prefer_shorter_route() {
        let mut rt = RoutingTable::new("own".to_string());
        rt.update("target", Route::via("192.168.1.5".to_string(), 3, "relay1".to_string()));
        rt.update("target", Route::via("192.168.1.6".to_string(), 2, "relay2".to_string()));
        let route = rt.get("target").unwrap();
        assert_eq!(route.hop_count, 2);
        assert_eq!(route.next_hop_ip, "192.168.1.6");
    }

    #[test]
    fn test_route_metrics_cost() {
        let mut metrics = RouteMetrics::default();
        assert!(metrics.cost(1) < metrics.cost(3));
        metrics.packet_loss_percent = 50;
        assert!(metrics.cost(1) > RouteMetrics::default().cost(1));
        metrics.consecutive_failures = 2;
        assert!(metrics.cost(1) > RouteMetrics::default().cost(1));
    }

    #[test]
    fn test_route_failure_threshold() {
        let mut route = Route::direct("192.168.1.1".to_string());
        assert!(route.metrics.is_usable());
        route.mark_failure();
        route.mark_failure();
        route.mark_failure();
        assert!(!route.metrics.is_usable());
    }

    #[test]
    fn test_rtt_smoothing() {
        let mut metrics = RouteMetrics::default();
        metrics.record_success(100);
        assert_eq!(metrics.avg_rtt_ms, 100);
        metrics.record_success(200);
        assert_eq!(metrics.avg_rtt_ms, 112);
        metrics.record_success(100);
        assert_eq!(metrics.avg_rtt_ms, 110);
    }
}
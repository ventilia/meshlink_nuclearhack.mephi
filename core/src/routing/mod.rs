// ==========================================
// ФАЙЛ: core/src/routing/mod.rs
// ИСПРАВЛЕНИЕ: устройства видели себя как ретрансляцию (5 hop)
// ==========================================
use std::collections::HashMap;
use std::time::{Duration, SystemTime};

// ИСПРАВЛЕНО: MAX_HOPS = 4 вместо 5.
// 5 hop — это уже слишком большая задержка для mesh-сети в локальной зоне.
pub const MAX_HOPS: u8 = 4;
const ROUTE_TIMEOUT_SECS: u64 = 60;

#[derive(Debug, Clone)]
pub struct Route {
    /// IP адрес следующего прыжка (не конечного узла)
    pub next_hop_ip: String,
    /// Количество хопов до назначения
    pub hop_count: u8,
    /// Peer ID промежуточного ретранслятора (None для прямых соединений)
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
        log::info!(
            "RoutingTable created for own_peer_id={}...",
            &own_peer_id[..own_peer_id.len().min(16)]
        );
        Self {
            routes: HashMap::new(),
            own_peer_id,
        }
    }

    pub fn update(&mut self, peer_id: &str, route: Route) -> bool {
        // ИСПРАВЛЕНО: Множественная защита от добавления себя
        if peer_id.is_empty() {
            return false;
        }
        if !self.own_peer_id.is_empty() && peer_id == self.own_peer_id {
            log::debug!(
                "RoutingTable: skipping own peerId {}...",
                &peer_id[..peer_id.len().min(8)]
            );
            return false;
        }
        // ИСПРАВЛЕНО: не добавляем маршруты с hop_count = 0
        if route.hop_count == 0 {
            log::warn!(
                "RoutingTable: skipping route with hop_count=0 for {}...",
                &peer_id[..peer_id.len().min(8)]
            );
            return false;
        }
        let should_update = match self.routes.get(peer_id) {
            None => true,
            Some(existing) => {
                // Обновляем если:
                // 1. Маршрут устарел
                // 2. Новый маршрут короче
                // 3. Прямой маршрут заменяет mesh-маршрут (даже той же длины)
                existing.is_expired()
                    || route.hop_count < existing.hop_count
                    || (route.hop_count == existing.hop_count
                    && route.via_peer_id.is_none()
                    && existing.via_peer_id.is_some())
            }
        };
        if should_update {
            log::debug!(
                "Route update: {}... → {} (hops={}, via={:?})",
                &peer_id[..peer_id.len().min(8)],
                route.next_hop_ip,
                route.hop_count,
                route.via_peer_id.as_ref().map(|v| &v[..v.len().min(8)])
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

    /// Обновить таблицу маршрутов из keepalive пакета.
    pub fn learn_from_keepalive(
        &mut self,
        from_ip: &str,
        from_peer_id: &str,
        peers: &[(String, Option<String>, u8)], // (peerId, ip, hopCount)
    ) {
        // ИСПРАВЛЕНО: Проверяем что from_peer_id != own_peer_id
        if from_peer_id == self.own_peer_id || from_peer_id.is_empty() {
            log::warn!(
                "learn_from_keepalive: skipping keepalive from own peer or empty id"
            );
            return;
        }
        // Добавляем прямой маршрут к отправителю
        self.update(from_peer_id, Route::direct(from_ip.to_string()));
        let own_id = self.own_peer_id.clone();
        for (peer_id, _peer_ip, hop_count) in peers {
            // ИСПРАВЛЕНО: несколько уровней фильтрации
            if peer_id.is_empty() {
                continue;
            }
            // Пропускаем себя (ключевое исправление бага с self-relay)
            if peer_id == &own_id {
                log::debug!(
                    "learn_from_keepalive: filtering own peerId from peer list"
                );
                continue;
            }
            // Пропускаем отправителя (уже добавлен как прямой)
            if peer_id == from_peer_id {
                continue;
            }
            // hop_count = 0 значит это запись о самом отправителе в его peer-листе
            if *hop_count == 0 {
                log::debug!(
                    "learn_from_keepalive: filtering hop_count=0 for {}...",
                    &peer_id[..peer_id.len().min(8)]
                );
                continue;
            }
            // Слишком длинный маршрут
            if *hop_count >= MAX_HOPS {
                log::debug!(
                    "learn_from_keepalive: filtering hop_count={} >= MAX_HOPS for {}...",
                    hop_count,
                    &peer_id[..peer_id.len().min(8)]
                );
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
            .filter(|(_, r)| !r| !r.is_expired())
            .map(|(peer_id, r)| (peer_id.clone(), r.next_hop_ip.clone(), r.hop_count))
            .collect()
    }

    pub fn size(&self) -> usize {
        self.routes.len()
    }

    pub fn remove(&mut self, peer_id: &str) {
        self.routes.remove(peer_id);
        log::debug!(
            "RoutingTable: removed route for {}...",
            &peer_id[..peer_id.len().min(8)]
        );
    }

    /// Установить собственный peer ID (если изменился после инициализации).
    pub fn set_own_peer_id(&mut self, own_peer_id: String) {
        if self.own_peer_id != own_peer_id {
            log::info!(
                "RoutingTable: own_peer_id updated to {}...",
                &own_peer_id[..own_peer_id.len().min(16)]
            );
            // Удаляем маршрут к себе если вдруг попал
            self.routes.remove(&own_peer_id);
        }
        self.own_peer_id = own_peer_id;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_no_self_relay() {
        let own_id = "aabbccdd11223344".to_string();
        let mut rt = RoutingTable::new(own_id.clone());
        // Симулируем keepalive где наш own_id присутствует в peer-листе
        let peers = vec![
            (own_id.clone(), Some("192.168.1.1".to_string()), 1u8),
            ("peer2222".to_string(), Some("192.168.1.2".to_string()), 1u8),
        ];
        rt.learn_from_keepalive("192.168.1.5", "peer1111", &peers);
        // Убеждаемся что маршрут к себе НЕ добавился
        assert!(rt.get(&own_id).is_none(), "Should not have route to own peer");
        // Маршрут к peer1111 должен быть (прямой)
        assert!(rt.get("peer1111").is_some());
        // Маршрут к peer2222 должен быть (через peer1111)
        assert!(rt.get("peer2222").is_some());
    }

    #[test]
    fn test_no_zero_hop_route() {
        let own_id = "aabbccdd11223344".to_string();
        let mut rt = RoutingTable::new(own_id.clone());
        let peers = vec![
            ("somepeer".to_string(), None, 0u8), // hop_count = 0 — должен быть отфильтрован
        ];
        rt.learn_from_keepalive("192.168.1.5", "peer1111", &peers);
        // Маршрут с hop=0 не должен добавиться
        assert!(rt.get("somepeer").is_none());
    }

    #[test]
    fn test_prefer_shorter_route() {
        let mut rt = RoutingTable::new("own".to_string());
        // Добавляем длинный маршрут
        rt.update("target", Route::via("192.168.1.5".to_string(), 3, "relay1".to_string()));
        // Приходит более короткий
        rt.update("target", Route::via("192.168.1.6".to_string(), 2, "relay2".to_string()));
        // Должен выбрать более короткий
        let route = rt.get("target").unwrap();
        assert_eq!(route.hop_count, 2);
        assert_eq!(route.next_hop_ip, "192.168.1.6");
    }
}
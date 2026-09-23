package com.mirage.spike.engine

// External HTTP routing is excluded; runtime checks inject PreparedLeg factories.
class GoogleDirectionsRouteEngine(cfg: ApiConfig) {
    suspend fun route(spec: RouteSpec): RouteResult {
        error("Network intentionally excluded")
    }
}

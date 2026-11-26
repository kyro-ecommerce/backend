package com.kyro.order.event;

import java.util.List;
import java.util.Map;

public record StockReleaseEvent(Long orderId, List<Map<String, Object>> items) {}

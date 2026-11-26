package com.kyro.order.event;

import java.util.List;
import java.util.Map;

public record CartConfirmedEvent(Long orderId, Long userId, List<Map<String, Object>> items) {}

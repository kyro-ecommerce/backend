package com.kyro.order.event;

public record PaymentStatusChangedEvent(
    Long orderId, String status, Long version, Boolean reviewRequired) {}

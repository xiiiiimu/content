package io.github.xiiiiimu.content.domain;

/** Sends PurchaseSettle messages keyed by reservationId. */
public interface SaleMessageSender {
 void send(String reservationId) throws Exception;
}

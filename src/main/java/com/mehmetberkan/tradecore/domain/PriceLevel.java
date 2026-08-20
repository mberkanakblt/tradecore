package com.mehmetberkan.tradecore.domain;

/**
 * Tek bir fiyat seviyesi. Emirler zaman önceliğine göre FIFO sırada,
 * intrusive doubly-linked list olarak tutulur bağlantı alanları
 * Order nesnesinin içinde (next/prev), ayrı node nesnesi yok.
 */
final class PriceLevel {

    Order head;
    Order tail;
    long totalQuantity;
    int orderCount;

    boolean isEmpty() {
        return head == null;
    }

    void addLast(Order order) {
        if (tail == null) {
            head = order;
            tail = order;
        } else {
            order.prev = tail;
            tail.next = order;
            tail = order;
        }
        totalQuantity += order.getRemainingQuantity();
        orderCount++;
    }

    void unlink(Order order) {
        if(order.prev != null) {
            order.prev.next = order.next;
        }else{
            head = order.next;
        }
        if(order.next != null) {
            order.next.prev = order.prev;
        }else {
            tail = order.prev;
        }
        order.prev = null;
        order.next = null;

        totalQuantity -= order.getRemainingQuantity();
        orderCount--;
    }

    /***
     * totalQuantity bir emrin kalan miktarını tutuyor
     * ama emir kısmer dolunca kalan miktar değişiyor.
     * yani kısmi dolum sonrası seviye toplamını düşürür.
     * @param amount
     */
    void reduceQuantity(long amount) {
        totalQuantity -= amount;
    }

}

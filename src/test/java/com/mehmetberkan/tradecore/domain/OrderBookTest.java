package com.mehmetberkan.tradecore.domain;

import com.mehmetberkan.tradecore.domain.enums.OrderStatus;
import com.mehmetberkan.tradecore.domain.enums.Side;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class OrderBookTest {

    @Test
    void shouldMatchFullyWhenPricesCross() {
        OrderBook book = new OrderBook();

        Order sell = new Order(1, Side.SELL, 455000, 100, 0);
        Order buy  = new Order(2, Side.BUY,  455000, 100, 0);

        // Defter boşken gelen satıcı eşleşemez, deftere yazılır
        List<Trade> noTrades = book.submit(sell);
        assertTrue(noTrades.isEmpty());
        assertEquals(455000, book.bestAsk());
        assertEquals(1, book.activeOrderCount());

        // Alıcı gelir, tam eşleşme olur
        List<Trade> trades = book.submit(buy);

        // 1) Trade doğru mu
        assertEquals(1, trades.size());
        Trade trade = trades.getFirst();
        assertEquals(2, trade.buyOrderSequence());
        assertEquals(1, trade.sellOrderSequence());
        assertEquals(455000, trade.price());
        assertEquals(100, trade.quantity());

        // 2) Emirlerin durumu doğru mu
        assertEquals(OrderStatus.FILLED, sell.getStatus());
        assertEquals(OrderStatus.FILLED, buy.getStatus());
        assertEquals(0, sell.getRemainingQuantity());
        assertEquals(0, buy.getRemainingQuantity());

        // 3) Defter temizlendi mi
        assertTrue(book.isEmpty());
        assertEquals(0, book.activeOrderCount());
        assertEquals(Long.MIN_VALUE, book.bestBid());
        assertEquals(Long.MAX_VALUE, book.bestAsk());
    }
    @Test
    void shouldWalkTheBookAcrossPriceLevels() {
        OrderBook book = new OrderBook();

        // Defterde üç ayrı fiyat seviyesinde satıcılar
        book.submit(new Order(1, Side.SELL, 455000, 50, 0));   // 45.50
        book.submit(new Order(2, Side.SELL, 456000, 50, 0));   // 45.60
        book.submit(new Order(3, Side.SELL, 457000, 50, 0));   // 45.70

        // Alıcı 45.60'a kadar ödemeye razı, 120 adet istiyor
        Order buy = new Order(4, Side.BUY, 456000, 120, 0);
        List<Trade> trades = book.submit(buy);

        // İki seviyeden doldu: 45.50'den 50, 45.60'tan 50 = 100
        assertEquals(2, trades.size());

        assertEquals(455000, trades.get(0).price());
        assertEquals(50, trades.get(0).quantity());
        assertEquals(1, trades.get(0).sellOrderSequence());

        assertEquals(456000, trades.get(1).price());
        assertEquals(50, trades.get(1).quantity());
        assertEquals(2, trades.get(1).sellOrderSequence());

        // Alıcının 20'si kaldı, deftere bid olarak yazıldı
        assertEquals(20, buy.getRemainingQuantity());
        assertEquals(OrderStatus.PARTIALLY_FILLED, buy.getStatus());
        assertEquals(456000, book.bestBid());
        assertEquals(20, book.quantityAt(Side.BUY, 456000));

        // 45.70 limitin üstündeydi, dokunulmadı
        assertEquals(457000, book.bestAsk());
        assertEquals(50, book.quantityAt(Side.SELL, 457000));

        // Defterde kalanlar: 1 bid + 1 ask
        assertEquals(2, book.activeOrderCount());
    }
    @Test
    void shouldSkipCancelledOrdersWhenMatching() {
        OrderBook book = new OrderBook();

        Order first  = new Order(1, Side.SELL, 455000, 100, 0);
        Order second = new Order(2, Side.SELL, 455000, 100, 0);

        book.submit(first);
        book.submit(second);
        assertEquals(2, book.activeOrderCount());

        // İlk satıcı iptal ediyor
        assertTrue(book.cancel(1));
        assertEquals(OrderStatus.CANCELLED, first.getStatus());
        assertEquals(1, book.activeOrderCount());

        // Aynı emri ikinci kez iptal etmek başarısız olmalı
        assertFalse(book.cancel(1));

        // Var olmayan bir emir de iptal edilemez
        assertFalse(book.cancel(999));

        // Alıcı gelir: iptal edilmiş seq 1 atlanmalı, seq 2 ile eşleşmeli
        Order buy = new Order(3, Side.BUY, 455000, 100, 0);
        List<Trade> trades = book.submit(buy);

        assertEquals(1, trades.size());
        assertEquals(2, trades.getFirst().sellOrderSequence());   // 1 DEĞİL
        assertEquals(100, trades.getFirst().quantity());

        assertEquals(OrderStatus.FILLED, second.getStatus());
        assertEquals(100, first.getRemainingQuantity());          // iptal edilen dokunulmadan kaldı

        // İptal edilmiş emir deque'ten atıldı, seviye temizlendi
        assertTrue(book.isEmpty());
        assertEquals(0, book.activeOrderCount());
        assertEquals(Long.MAX_VALUE, book.bestAsk());
    }
    @Test
    void shouldRespectTimePriorityAtSamePrice() {
        OrderBook book = new OrderBook();

        book.submit(new Order(1, Side.SELL, 455000, 50, 0));
        book.submit(new Order(2, Side.SELL, 455000, 50, 0));
        book.submit(new Order(3, Side.SELL, 455000, 50, 0));

        // Sadece 50 alıyor: aynı fiyatta ilk gelen dolmalı
        List<Trade> trades = book.submit(new Order(4, Side.BUY, 455000, 50, 0));

        assertEquals(1, trades.size());
        assertEquals(1, trades.getFirst().sellOrderSequence());

        assertEquals(100, book.quantityAt(Side.SELL, 455000));
        assertEquals(2, book.activeOrderCount());
    }

    @Test
    void shouldTradeAtRestingOrderPrice() {
        OrderBook book = new OrderBook();

        book.submit(new Order(1, Side.SELL, 455000, 100, 0));

        // Alıcı 46.00'a kadar ödemeye razı ama 45.50'den alacak
        List<Trade> trades = book.submit(new Order(2, Side.BUY, 460000, 100, 0));

        assertEquals(455000, trades.getFirst().price());
        assertTrue(book.isEmpty());
    }
    @Test
    void shouldMaintainInvariantsUnderRandomLoad() {
        OrderBook book = new OrderBook();
        Random random = new Random(42);   // sabit seed: hata çıkarsa tekrar üretilebilir

        long totalSubmitted = 0;   // sisteme giren toplam miktar
        long totalMatched = 0;     // eşleşen toplam miktar (tek taraf sayılır)

        List<Long> liveSequences = new ArrayList<>();

        for (long seq = 1; seq <= 100_000; seq++) {

            // %20 ihtimalle iptal, %80 yeni emir
            if (!liveSequences.isEmpty() && random.nextInt(100) < 20) {
                long victim = liveSequences.remove(random.nextInt(liveSequences.size()));
                book.cancel(victim);
            } else {
                Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
                long price = 450_000 + random.nextInt(20) * 1_000;   // 45.00 – 45.19
                long qty = 1 + random.nextInt(100);

                Order order = new Order(seq, side, price, qty, 0);
                totalSubmitted += qty;

                List<Trade> trades = book.submit(order);
                for (Trade t : trades) {
                    totalMatched += t.quantity();
                }

                if (order.getRemainingQuantity() > 0) {
                    liveSequences.add(seq);
                }
            }

            book.validateInvariants();
        }

        // Muhasebe: giren miktar = eşleşen (iki taraf) + defterde kalan + iptal edilen
        long restingQuantity = book.totalRestingQuantity();
        assertEquals(totalSubmitted, totalMatched * 2 + restingQuantity + book.cancelledQuantity());
    }

}
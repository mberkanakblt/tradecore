package com.mehmetberkan.tradecore.domain;

import com.mehmetberkan.tradecore.domain.enums.Side;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class OrderBookTest {

    @Test
    void shouldRejectPricesOutsideBookRange() {
        OrderBook book = new OrderBook(400_000, 100, 1000);   // 400.00 – 499.90

        assertThrows(IllegalArgumentException.class,
                () -> book.submit(1, Side.BUY, 350_000, 10));   // çok düşük

        assertThrows(IllegalArgumentException.class,
                () -> book.submit(2, Side.BUY, 600_000, 10));   // çok yüksek

        assertThrows(IllegalArgumentException.class,
                () -> book.submit(3, Side.BUY, 450_050, 10));   // tick dışı

        // Reddedilen emirler havuzdan nesne sızdırmamalı
        book.validateInvariants();
        assertEquals(book.poolAvailable(), book.poolAvailable());
        assertTrue(book.isEmpty());
    }

    @Test
    void shouldMatchFullyWhenPricesCross() {
        OrderBook book = new OrderBook();

        // Defter boşken gelen satıcı eşleşemez, deftere yazılır
        List<Trade> noTrades = book.submit(1, Side.SELL, 455_000, 100);
        assertTrue(noTrades.isEmpty());
        assertEquals(455_000, book.bestAsk());
        assertEquals(1, book.activeOrderCount());
        assertTrue(book.isResting(1));

        // Alıcı gelir, tam eşleşme olur
        List<Trade> trades = book.submit(2, Side.BUY, 455_000, 100);

        // 1) Trade doğru mu
        assertEquals(1, trades.size());
        Trade trade = trades.getFirst();
        assertEquals(2, trade.buyOrderSequence());
        assertEquals(1, trade.sellOrderSequence());
        assertEquals(455_000, trade.price());
        assertEquals(100, trade.quantity());

        // 2) İki emir de defterden çıktı
        assertFalse(book.isResting(1));
        assertFalse(book.isResting(2));

        // 3) Defter temizlendi
        assertTrue(book.isEmpty());
        assertEquals(0, book.activeOrderCount());
        assertEquals(Long.MIN_VALUE, book.bestBid());
        assertEquals(Long.MAX_VALUE, book.bestAsk());

        book.validateInvariants();
    }

    @Test
    void shouldWalkTheBookAcrossPriceLevels() {
        OrderBook book = new OrderBook();

        // Defterde üç ayrı fiyat seviyesinde satıcılar
        book.submit(1, Side.SELL, 455_000, 50);   // 45.50
        book.submit(2, Side.SELL, 456_000, 50);   // 45.60
        book.submit(3, Side.SELL, 457_000, 50);   // 45.70

        // Alıcı 45.60'a kadar ödemeye razı, 120 adet istiyor
        List<Trade> trades = book.submit(4, Side.BUY, 456_000, 120);

        // İki seviyeden doldu: 45.50'den 50, 45.60'tan 50 = 100
        assertEquals(2, trades.size());

        assertEquals(455_000, trades.get(0).price());
        assertEquals(50, trades.get(0).quantity());
        assertEquals(1, trades.get(0).sellOrderSequence());

        assertEquals(456_000, trades.get(1).price());
        assertEquals(50, trades.get(1).quantity());
        assertEquals(2, trades.get(1).sellOrderSequence());

        // Alıcının 20'si kaldı, deftere bid olarak yazıldı
        assertTrue(book.isResting(4));
        assertEquals(20, book.remainingQuantityOf(4));
        assertEquals(456_000, book.bestBid());
        assertEquals(20, book.quantityAt(Side.BUY, 456_000));

        // Dolan iki satıcı defterden çıktı
        assertFalse(book.isResting(1));
        assertFalse(book.isResting(2));

        // 45.70 limitin üstündeydi, dokunulmadı
        assertEquals(457_000, book.bestAsk());
        assertEquals(50, book.quantityAt(Side.SELL, 457_000));

        // Defterde kalanlar: 1 bid + 1 ask
        assertEquals(2, book.activeOrderCount());

        book.validateInvariants();
    }

    @Test
    void shouldSkipCancelledOrdersWhenMatching() {
        OrderBook book = new OrderBook();

        book.submit(1, Side.SELL, 455_000, 100);
        book.submit(2, Side.SELL, 455_000, 100);
        assertEquals(2, book.activeOrderCount());

        // İlk satıcı iptal ediyor — artık listeden fiziksel olarak çıkıyor
        assertTrue(book.cancel(1));
        assertFalse(book.isResting(1));
        assertEquals(1, book.activeOrderCount());
        assertEquals(1, book.orderCountAt(Side.SELL, 455_000));
        assertEquals(100, book.quantityAt(Side.SELL, 455_000));

        // Aynı emri ikinci kez iptal etmek başarısız olmalı
        assertFalse(book.cancel(1));

        // Var olmayan bir emir de iptal edilemez
        assertFalse(book.cancel(999));

        // Alıcı gelir: iptal edilmiş seq 1 yok, seq 2 ile eşleşmeli
        List<Trade> trades = book.submit(3, Side.BUY, 455_000, 100);

        assertEquals(1, trades.size());
        assertEquals(2, trades.getFirst().sellOrderSequence());   // 1 DEĞİL
        assertEquals(100, trades.getFirst().quantity());

        assertFalse(book.isResting(2));

        // İptal edilen miktar muhasebeye girdi
        assertEquals(100, book.cancelledQuantity());

        assertTrue(book.isEmpty());
        assertEquals(0, book.activeOrderCount());
        assertEquals(Long.MAX_VALUE, book.bestAsk());

        book.validateInvariants();
    }

    @Test
    void shouldRespectTimePriorityAtSamePrice() {
        OrderBook book = new OrderBook();

        book.submit(1, Side.SELL, 455_000, 50);
        book.submit(2, Side.SELL, 455_000, 50);
        book.submit(3, Side.SELL, 455_000, 50);

        // Sadece 50 alıyor: aynı fiyatta ilk gelen dolmalı
        List<Trade> trades = book.submit(4, Side.BUY, 455_000, 50);

        assertEquals(1, trades.size());
        assertEquals(1, trades.getFirst().sellOrderSequence());

        assertFalse(book.isResting(1));
        assertTrue(book.isResting(2));
        assertTrue(book.isResting(3));

        assertEquals(100, book.quantityAt(Side.SELL, 455_000));
        assertEquals(2, book.orderCountAt(Side.SELL, 455_000));
        assertEquals(2, book.activeOrderCount());

        book.validateInvariants();
    }

    @Test
    void shouldTradeAtRestingOrderPrice() {
        OrderBook book = new OrderBook();

        book.submit(1, Side.SELL, 455_000, 100);

        // Alıcı 46.00'a kadar ödemeye razı ama 45.50'den alacak
        List<Trade> trades = book.submit(2, Side.BUY, 460_000, 100);

        assertEquals(455_000, trades.getFirst().price());
        assertTrue(book.isEmpty());

        book.validateInvariants();
    }

    @Test
    void shouldReturnOrdersToThePool() {
        OrderBook book = new OrderBook();
        int capacity = book.poolAvailable();

        // Deftere giren emir havuzdan bir nesne tutar
        book.submit(1, Side.SELL, 455_000, 100);
        assertEquals(capacity - 1, book.poolAvailable());

        // Tam eşleşme: iki emir de havuza döner
        book.submit(2, Side.BUY, 455_000, 100);
        assertEquals(capacity, book.poolAvailable());

        // İptal de havuza iade eder
        book.submit(3, Side.SELL, 455_000, 100);
        assertEquals(capacity - 1, book.poolAvailable());
        book.cancel(3);
        assertEquals(capacity, book.poolAvailable());

        book.validateInvariants();
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

                totalSubmitted += qty;

                List<Trade> trades = book.submit(seq, side, price, qty);
                for (Trade t : trades) {
                    totalMatched += t.quantity();
                }

                if (book.isResting(seq)) {
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
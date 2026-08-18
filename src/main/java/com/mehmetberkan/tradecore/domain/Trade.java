package com.mehmetberkan.tradecore.domain;

public record Trade (
    long tradeId,
    long buyOrderSequence,
    long sellOrderSequence,
    long price,
    long quantity,
    long timestampNanos){
}

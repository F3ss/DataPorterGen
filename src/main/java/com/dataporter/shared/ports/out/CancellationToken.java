package com.dataporter.shared.ports.out;

@FunctionalInterface
public interface CancellationToken { boolean isCancellationRequested(); }

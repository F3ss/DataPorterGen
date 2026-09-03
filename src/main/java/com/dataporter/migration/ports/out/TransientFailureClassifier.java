package com.dataporter.migration.ports.out;

@FunctionalInterface
public interface TransientFailureClassifier { boolean isTransient(RuntimeException failure); }

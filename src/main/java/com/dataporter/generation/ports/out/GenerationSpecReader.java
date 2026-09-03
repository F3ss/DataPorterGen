package com.dataporter.generation.ports.out;

import com.dataporter.generation.domain.GenerationSpec;

@FunctionalInterface
public interface GenerationSpecReader { GenerationSpec read(); }

package com.dataporter.generation.domain;

/** Target behavior requested for GENERATE mode; only {@link #APPEND_TO_EXISTING} is supported. */
public enum GenerationTargetMode { APPEND_TO_EXISTING, RECREATE_TARGET, MERGE_TARGET }

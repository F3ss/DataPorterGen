package com.dataporter.shared.ports.out;

import com.dataporter.shared.bson.DataBatch;

public interface BatchCursor extends AutoCloseable {
    /** Returns the next batch or null at end of stream. */
    DataBatch next();
    @Override void close();
}

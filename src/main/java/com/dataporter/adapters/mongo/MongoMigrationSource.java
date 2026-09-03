package com.dataporter.adapters.mongo;

import com.dataporter.migration.domain.MigrationPlan;
import com.dataporter.migration.ports.out.MigrationSource;
import com.dataporter.shared.domain.Endpoint;
import com.dataporter.shared.error.SourceConnectionException;

public final class MongoMigrationSource extends AbstractMongoReader implements MigrationSource {
    public MongoMigrationSource(Endpoint endpoint) { super(endpoint); }

    @Override public void checkConnection() {
        try { super.checkConnection(); }
        catch (RuntimeException e) { throw new SourceConnectionException("Cannot connect to source " + endpoint.safeUri(), e); }
    }

    @Override public MigrationPlan inspect() { return inspectPlan(); }
}

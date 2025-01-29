package org.opensearch.migrations.metadata.tracing;

import org.opensearch.migrations.bulkload.framework.tracing.TrackingTestContextFactory;
import org.opensearch.migrations.tracing.IContextTracker;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;

public class MetadataMigrationTestContext extends RootMetadataMigrationContext {
    public final InMemoryInstrumentationBundle inMemoryInstrumentationBundle;

    public static TrackingTestContextFactory<MetadataMigrationTestContext> factory() {
        return TrackingTestContextFactory.factoryViaCtor(MetadataMigrationTestContext.class);
    }

    public MetadataMigrationTestContext(
        InMemoryInstrumentationBundle inMemoryInstrumentationBundle,
        IContextTracker contextTracker
    ) {
        super(inMemoryInstrumentationBundle.openTelemetrySdk, contextTracker);
        this.inMemoryInstrumentationBundle = inMemoryInstrumentationBundle;
    }
}

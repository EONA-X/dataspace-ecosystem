package org.eclipse.dse.migration.flyway;

import org.eclipse.edc.junit.annotations.ComponentTest;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.persistence.EdcPersistenceException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;

@ComponentTest
class PostgresMigrationBarrierTest {

    private final Monitor monitor = mock();
    private final MigrationBarrier barrier = new MigrationBarrier(monitor);

    @Test
    void awaitAllBaselines_releasesOnceEveryExpectedModuleHasSignaled() throws Exception {
        var database = PostgresTestDatabase.createEmpty();
        var expected = Set.of("control-plane", "data-plane");
        var executor = Executors.newFixedThreadPool(2);

        try {
            Callable<Void> controlPlane = () -> {
                barrier.blockUntilSiblingsFinishNonSharedMigrations(database.dataSource(), "control-plane", expected, Duration.ofSeconds(10));
                return null;
            };
            Callable<Void> dataPlane = () -> {
                barrier.blockUntilSiblingsFinishNonSharedMigrations(database.dataSource(), "data-plane", expected, Duration.ofSeconds(10));
                return null;
            };

            List<Future<Void>> results = executor.invokeAll(List.of(controlPlane, dataPlane));
            // get() rethrows if either call failed or timed out -- that is the assertion.
            for (Future<Void> result : results) {
                result.get();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void awaitAllBaselines_timesOut_whenAnExpectedModuleNeverSignals() {
        var database = PostgresTestDatabase.createEmpty();

        assertThatExceptionOfType(EdcPersistenceException.class)
                .isThrownBy(() -> barrier.blockUntilSiblingsFinishNonSharedMigrations(
                        database.dataSource(), "control-plane", Set.of("control-plane", "data-plane"), Duration.ofMillis(600)))
                .withMessageContaining("data-plane");
    }

    @Test
    void awaitAllBaselines_returnsImmediately_whenThisModuleIsAllThatsExpected() {
        var database = PostgresTestDatabase.createEmpty();

        // No exception, no timeout: reaching the end of this test method is the assertion.
        barrier.blockUntilSiblingsFinishNonSharedMigrations(database.dataSource(), "control-plane", Set.of("control-plane"), Duration.ofSeconds(1));
    }
}

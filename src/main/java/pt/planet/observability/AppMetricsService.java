package pt.planet.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class AppMetricsService {

    private final MeterRegistry meterRegistry;

    public void recordImport(String status, int total, int successful, int failed, Duration duration) {
        Counter.builder("file.import.count")
                .description("Total number of file import requests")
                .tag("status", status)
                .register(meterRegistry)
                .increment();

        Counter.builder("file.import.records")
                .description("Total records processed in imports")
                .tag("type", "successful")
                .register(meterRegistry)
                .increment(successful);

        Counter.builder("file.import.records")
                .description("Total records processed in imports")
                .tag("type", "failed")
                .register(meterRegistry)
                .increment(failed);

        Timer.builder("file.import.duration")
                .description("Time taken to process file imports")
                .register(meterRegistry)
                .record(duration);
    }

    public void recordExport(String format, int recordCount, Duration duration) {
        Counter.builder("file.export.count")
                .description("Total number of file export requests")
                .tag("format", format.toLowerCase())
                .register(meterRegistry)
                .increment();

        Counter.builder("file.export.records")
                .description("Total customer records exported")
                .tag("format", format.toLowerCase())
                .register(meterRegistry)
                .increment(recordCount);

        Timer.builder("file.export.duration")
                .description("Time taken to generate file exports")
                .tag("format", format.toLowerCase())
                .register(meterRegistry)
                .record(duration);
    }
}

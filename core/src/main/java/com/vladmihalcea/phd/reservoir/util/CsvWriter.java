package com.vladmihalcea.phd.reservoir.util;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Minimal CSV writer that emits a header plus rows into the repository's {@code metrics/} tree, matching
 * the one-subfolder-per-figure convention inherited from the dto-entity-auto-sync template so the same
 * gnuplot scripts can consume the output.
 *
 * @author Vlad Mihalcea
 */
public final class CsvWriter implements AutoCloseable {

    private final Writer writer;

    public CsvWriter(Path file, String... header) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            this.writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
            writeRow((Object[]) header);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void writeRow(Object... cells) {
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cells.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(format(cells[i]));
            }
            sb.append('\n');
            writer.write(sb.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String format(Object cell) {
        if (cell instanceof Double d) {
            return String.format(Locale.ROOT, "%.6g", d);
        }
        if (cell instanceof Float f) {
            return String.format(Locale.ROOT, "%.6g", f);
        }
        return String.valueOf(cell);
    }

    @Override
    public void close() {
        try {
            writer.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Resolves a path under the repository's top-level {@code metrics/} directory, creating the
     * per-figure subfolder. Works whether tests run from the module or the repository root.
     */
    public static Path metricsPath(String figure, String fileName) {
        Path base = Path.of("").toAbsolutePath();
        Path metrics = base.resolve("metrics");
        if (!Files.exists(metrics) && base.getParent() != null) {
            Path parentMetrics = base.getParent().resolve("metrics");
            if (Files.exists(parentMetrics) || base.getFileName().toString().equals("core")) {
                metrics = parentMetrics;
            }
        }
        return metrics.resolve(figure).resolve(fileName);
    }
}

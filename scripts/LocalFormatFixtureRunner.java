package com.kafkasl.phonewhisper;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Research fixtures only, using the existing production bindings and native library. */
public final class LocalFormatFixtureRunner {
    private static String utf8(byte[] bytes) throws Exception {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("MODEL DIRECTORY THREADS DEADLINE_MS");
        Path directory = Path.of(args[1]);
        int threads = Integer.parseInt(args[2]);
        long deadlineMs = Long.parseLong(args[3]);
        LocalFormatBindings api = new LocalFormatBindings();
        long loading = System.nanoTime();
        long handle = api.open(args[0].getBytes(StandardCharsets.UTF_8), 4096, threads);
        Files.writeString(directory.resolve("model-load.metrics"), Long.toString((System.nanoTime() - loading) / 1_000_000));
        if (handle == 0) throw new IllegalStateException("Model open failed");
        try {
            long generation = 1;
            for (String id : Files.readAllLines(directory.resolve("index.txt"))) {
                byte[] prompt = Files.readAllBytes(directory.resolve(id + ".prompt"));
                byte[] grammar = Files.readAllBytes(directory.resolve(id + ".grammar"));
                int budget = Integer.parseInt(Files.readString(directory.resolve(id + ".budget")).trim());
                long started = System.nanoTime();
                long[] first = {-1};
                byte[][] partial = {new byte[0]};
                String[] previous = {""};
                byte[] result = api.generate(handle, generation++, prompt, grammar, budget, deadlineMs, bytes -> {
                    try {
                        String next = utf8(bytes);
                        if (!next.startsWith(previous[0])) throw new IllegalStateException("Non-cumulative stream");
                        previous[0] = next;
                        partial[0] = bytes.clone();
                        if (first[0] < 0 && !next.isBlank()) first[0] = (System.nanoTime() - started) / 1_000_000;
                    } catch (Exception error) {
                        throw new IllegalStateException("Invalid streamed UTF-8", error);
                    }
                });
                long total = (System.nanoTime() - started) / 1_000_000;
                if (result != null && !Arrays.equals(result, partial[0]))
                    throw new IllegalStateException("Final return differs from cumulative stream");
                Files.write(directory.resolve(id + ".partial"), partial[0]);
                Files.write(directory.resolve(id + ".output"), result == null ? new byte[0] : result);
                Files.writeString(directory.resolve(id + ".metrics"), first[0] + "\t" + total + "\t" + (result != null));
                System.out.println("FIXTURE " + id + " complete=" + (result != null) + " first_ms=" + first[0] + " total_ms=" + total);
            }
        } finally { api.close(handle); }
    }
}

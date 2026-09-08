#!/usr/bin/env bash
set -euo pipefail
repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
jdk="${DICTAI_JAVA_HOME:-/home/ullie/.cache/dictai-build-tools/java/usr/lib/jvm/java-17-openjdk-amd64}"
cmake_bin="${DICTAI_CMAKE:-/home/ullie/.cache/dictai-build-tools/sdk/cmake/3.22.1/bin/cmake}"
source_dir="${DICTAI_LLAMA_SOURCE_DIR:-/home/ullie/.cache/dictai-build-tools/llama.cpp-v0.1.2}"
build_dir="${DICTAI_JNI_HOST_BUILD_DIR:-$repo_root/.native-cache/local-format-jni-host}"
model="${1:-$repo_root/app/src/localFormatPrototype/assets/local-format/LFM2.5-350M-Q4_K_M.gguf}"
[[ -f "$model" ]]
[[ "$(git -C "$source_dir" rev-parse HEAD)" == 1511ce3bc3f087376c8526b4ad07100bfabb277f ]]
"$cmake_bin" -S "$repo_root/app/src/main/cpp/llm" -B "$build_dir" \
    -DCMAKE_BUILD_TYPE=Release -DDICTAI_ARM82_VARIANT=OFF \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DDICTAI_JAVA_HOME="$jdk" -DLLAMA_SOURCE_DIR="$source_dir"
"$cmake_bin" --build "$build_dir" --target dictai_llm --parallel "${DICTAI_BUILD_JOBS:-3}"
mkdir -p "$build_dir/java"
cat > "$build_dir/java/Main.java" <<'JAVA'
package com.kafkasl.phonewhisper;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.Arrays;
interface LocalFormatChunkSink { void onBytes(byte[] bytes); }
final class LocalFormatBindings {
    static { System.loadLibrary("dictai_llm"); }
    native boolean supportsArm82();
    native long open(byte[] path, int contextSize, int threads);
    native byte[] generate(long handle, long generation, byte[] prompt, byte[] grammar,
        int maxTokens, long timeoutMs, LocalFormatChunkSink sink);
    native void cancel(long handle, long generation);
    native void close(long handle);
}
public final class Main {
    static byte[] b(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static String utf8(byte[] bytes) {
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException e) { throw new AssertionError("Invalid UTF8 chunk", e); }
    }
    static String literal(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }
    public static void main(String[] args) throws Exception {
        LocalFormatBindings api = new LocalFormatBindings();
        final long h = api.open(b(args[0]), 4096, 2);
        check(h != 0, "Model open failed");
        String exact = "Maëlys a dit \"ne pas envoyer 23 dossiers\" à 14 h 30.\nFichier: C:\\Notes\\rapport_23.txt 😀.";
        byte[] grammar = b("root ::= " + literal(exact) + "\n");
        byte[] prompt = b("<|startoftext|><|im_start|>system\nCopy the text exactly.<|im_end|>\n" +
            "<|im_start|>user\n" + exact + "<|im_end|>\n<|im_start|>assistant\n");
        LocalFormatChunkSink silent = chunk -> { utf8(chunk); };
        LocalFormatChunkSink forbidden = chunk -> { throw new AssertionError("Invalid grammar generated text"); };
        try {
            check(api.generate(h, 1, prompt, b("root ::= (\n"), 256, 60000, forbidden) == null, "Malformed grammar accepted");
            check(api.generate(h, 2, prompt, new byte[0], 256, 60000, forbidden) == null, "Empty grammar accepted");
            final String[] previous = {""};
            byte[] out = api.generate(h, 3, prompt, grammar, 256, 60000, chunk -> {
                String next = utf8(chunk);
                check(next.startsWith(previous[0]), "Non-cumulative stream");
                previous[0] = next;
            });
            check(Arrays.equals(out, b(exact)), "Literal fidelity / EOG failure");
            check(previous[0].equals(exact), "Final chunk differs from return value");
            byte[] cut = api.generate(h, 4, prompt, grammar, 256, 60000, chunk -> {
                utf8(chunk);
                api.cancel(h, 4);
            });
            check(cut == null, "Cancellation returned partial success");
            check(Arrays.equals(api.generate(h, 5, prompt, grammar, 256, 60000, silent), b(exact)), "Retry after cancel failed");
            check(api.generate(h, 6, prompt, grammar, 1, 60000, silent) == null, "Token limit returned partial success");
            byte[] longPrompt = b(new String(prompt, StandardCharsets.UTF_8) + "texte ".repeat(1200));
            check(api.generate(h, 7, longPrompt, grammar, 256, 1, silent) == null, "Prefill deadline ignored");
            System.out.println("JNI grammar: malformed/empty rejection, exact Unicode+quotes+numbers, cumulative UTF8, EOG, cancel/retry, truncation, deadline: PASS");
            if (args.length > 1) {
                var directory = java.nio.file.Path.of(args[1]);
                long generation = 100;
                for (String id : java.nio.file.Files.readAllLines(directory.resolve("index.txt"))) {
                    var input = java.nio.file.Files.readAllBytes(directory.resolve(id + ".prompt"));
                    var rule = java.nio.file.Files.readAllBytes(directory.resolve(id + ".grammar"));
                    int budget = Integer.parseInt(java.nio.file.Files.readString(directory.resolve(id + ".budget")));
                    final long start = System.nanoTime();
                    final long[] first = {-1};
                    byte[] result = api.generate(h, generation++, input, rule, budget, 20000, chunk -> {
                        utf8(chunk);
                        if (first[0] < 0) first[0] = (System.nanoTime() - start) / 1000000;
                    });
                    long total = (System.nanoTime() - start) / 1000000;
                    java.nio.file.Files.write(directory.resolve(id + ".output"), result == null ? new byte[0] : result);
                    java.nio.file.Files.writeString(directory.resolve(id + ".metrics"), first[0] + "\t" + total + "\t" + (result != null));
                    System.out.println("JNI layout " + id + " complete=" + (result != null) + " ms=" + total);
                }
            }
        } finally { api.close(h); }
    }
}
JAVA
"$jdk/bin/javac" -encoding UTF-8 -d "$build_dir/java" "$build_dir/java/Main.java"
python3 -B "$repo_root/scripts/jni_layout_fixtures.py" prepare "$build_dir/layout"
"$jdk/bin/java" -Djava.library.path="$build_dir/out" -cp "$build_dir/java" com.kafkasl.phonewhisper.Main "$model" "$build_dir/layout"
python3 -B "$repo_root/scripts/jni_layout_fixtures.py" report "$build_dir/layout"

package com.kafkasl.phonewhisper

/** Targeted benchmark criteria, separate from text fidelity. Positions are after source word N. */
internal data class LayoutGroupingExpectation(
    val required: Set<Int>,
    val allowed: Set<Int>? = null,
    val forbidden: Set<Int> = emptySet(),
) {
    data class Result(val missing: Set<Int>, val unexpected: Set<Int>) {
        val passed: Boolean get() = missing.isEmpty() && unexpected.isEmpty()
    }

    fun evaluate(request: LocalFormatRequest, output: String?): Result? {
        if (request.layoutKind == null) return null
        val accepted = request.acceptOutput(output) ?: return null
        val segments = accepted.lines().filter { it.isNotBlank() }.map {
            if (request.layoutKind == LocalLayoutKind.LIST) it.removePrefix("• ") else it
        }
        var count = 0
        val boundaries = segments.dropLast(1).map {
            count += Regex("[^\\s\\p{Z}\\u0085]+").findAll(it).count()
            count
        }.toSet()
        return Result(required - boundaries,
            (allowed?.let { boundaries - it } ?: emptySet()) + boundaries.intersect(forbidden))
    }
}

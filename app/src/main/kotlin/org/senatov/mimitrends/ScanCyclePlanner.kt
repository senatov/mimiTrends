package org.senatov.mimitrends

internal class ScanCyclePlanner(
    private val maximumSymbolsPerCycle: Int = DEFAULT_MAXIMUM_SYMBOLS_PER_CYCLE
) {
    private var cycle = 0
    private var rotationOffset = 0
    private val priorityUntilCycle = mutableMapOf<String, Int>()

    @Synchronized
    fun order(symbols: List<String>): List<String> {
        if (symbols.isEmpty()) return emptyList()
        val distinct = symbols.distinct()
        priorityUntilCycle.entries.removeIf { it.value <= cycle }
        val priority = distinct.filter { it in priorityUntilCycle }
        val ordinary = distinct.filterNot { it in priorityUntilCycle }
        val ordered = interleave(priority, ordinary).take(maximumSymbolsPerCycle.coerceAtLeast(1))
        cycle++
        val hasBothRegions = distinct.any { it.contains('.') } && distinct.any { !it.contains('.') }
        rotationOffset += when {
            distinct.size > maximumSymbolsPerCycle -> maximumSymbolsPerCycle
            hasBothRegions -> 2
            else -> 1
        }
        return ordered
    }

    @Synchronized
    fun replacePriority(symbols: Collection<String>) {
        symbols.forEach { priorityUntilCycle[it.uppercase()] = cycle + PRIORITY_CYCLES }
    }

    @Synchronized
    fun reset() {
        cycle = 0
        rotationOffset = 0
        priorityUntilCycle.clear()
    }

    private fun interleave(priority: List<String>, ordinary: List<String>): List<String> =
        interleaveRegions(priority) + interleaveRegions(ordinary)

    private fun interleaveRegions(symbols: List<String>): List<String> {
        val usSymbols = symbols.filterNot { it.contains('.') }
        val europeSymbols = symbols.filter { it.contains('.') }
        val regionalOffset = if (usSymbols.isNotEmpty() && europeSymbols.isNotEmpty()) rotationOffset / 2 else rotationOffset
        val us = ArrayDeque(rotate(usSymbols, regionalOffset))
        val europe = ArrayDeque(rotate(europeSymbols, regionalOffset))
        val result = ArrayList<String>(symbols.size)
        var preferEurope = cycle % 2 == 1
        while (us.isNotEmpty() || europe.isNotEmpty()) {
            val preferred = if (preferEurope) europe else us
            val fallback = if (preferEurope) us else europe
            (if (preferred.isNotEmpty()) preferred else fallback).removeFirstOrNull()?.let(result::add)
            preferEurope = !preferEurope
        }
        return result
    }

    private fun rotate(symbols: List<String>, offset: Int): List<String> {
        if (symbols.size < 2) return symbols
        val start = offset % symbols.size
        return symbols.drop(start) + symbols.take(start)
    }

    private companion object {
        const val PRIORITY_CYCLES = 3
        const val DEFAULT_MAXIMUM_SYMBOLS_PER_CYCLE = 75
    }
}

package org.senatov.mimitrends.db

import org.senatov.mimitrends.model.BrokerTrade
import kotlin.math.abs

internal object BrokerTradeMatcher {
    fun matches(description: String, companyName: String): Boolean {
        val transactionName = normalize(description)
        val instrumentName = normalize(companyName)
        if (transactionName.isBlank() || instrumentName.isBlank()) return false
        return transactionName == instrumentName || transactionName.startsWith(instrumentName) ||
            instrumentName.startsWith(transactionName)
    }

    fun pair(symbol: String, executions: List<BrokerExecution>): List<BrokerTrade> =
        reconcile(symbol, executions).trades

    fun reconcile(symbol: String, executions: List<BrokerExecution>): BrokerTradeReconciliation {
        val reconciliations = executions.groupBy { it.currency.uppercase() }.values.map { values ->
            reconcileSingleCurrency(symbol, values)
        }
        return BrokerTradeReconciliation(
            reconciliations.flatMap(BrokerTradeReconciliation::trades)
                .sortedWith(compareBy(BrokerTrade::entryEpochSeconds, { it.exitEpochSeconds ?: Long.MAX_VALUE })),
            reconciliations.sumOf(BrokerTradeReconciliation::correctedOrder),
            reconciliations.sumOf(BrokerTradeReconciliation::unmatchedSells)
        )
    }

    private fun reconcileSingleCurrency(
        symbol: String,
        executions: List<BrokerExecution>
    ): BrokerTradeReconciliation {
        val matcher = FifoMatcher(symbol)
        executions.groupBy(BrokerExecution::epochSeconds).toSortedMap().forEach { (_, sameSecond) ->
            val pending = sameSecond.sortedBy(BrokerExecution::id).toMutableList()
            pending.firstOrNull { it.isSell() && matcher.closesExactly(it.shares) }?.let {
                pending.remove(it)
                matcher.sell(it)
            }
            pending.filter { it.isBuy() }.forEach(matcher::buy)
            pending.filter { it.isSell() }.forEach(matcher::sell)
        }
        return matcher.result()
    }

    private class FifoMatcher(private val symbol: String) {
        private val lots = ArrayDeque<ExecutionRemainder>()
        private val deferredSells = mutableListOf<ExecutionRemainder>()
        private val trades = mutableListOf<BrokerTrade>()
        private val correctedSellIds = mutableSetOf<Long>()

        fun closesExactly(quantity: Double): Boolean = sameQuantity(lots.sumOf { it.remaining }, quantity)

        fun buy(execution: BrokerExecution) {
            val buy = ExecutionRemainder(execution)
            while (buy.hasRemaining()) {
                val sellIndex = deferredSells.indexOfFirst {
                    buy.execution.epochSeconds - it.execution.epochSeconds in 0..CORRECTION_WINDOW_SECONDS
                }
                if (sellIndex < 0) break
                val sell = deferredSells[sellIndex]
                val quantity = minOf(buy.remaining, sell.remaining)
                trades += completedTrade(listOf(buy.slice(quantity)), sell.slice(quantity), sell.execution.epochSeconds)
                correctedSellIds += sell.execution.id
                if (!sell.hasRemaining()) deferredSells.removeAt(sellIndex)
            }
            if (buy.hasRemaining()) lots.addLast(buy)
        }

        fun sell(execution: BrokerExecution) {
            val sell = ExecutionRemainder(execution)
            val consumed = mutableListOf<ExecutionSlice>()
            while (sell.hasRemaining() && lots.isNotEmpty()) {
                val lot = lots.first()
                val quantity = minOf(lot.remaining, sell.remaining)
                consumed += lot.slice(quantity)
                sell.consume(quantity)
                if (!lot.hasRemaining()) lots.removeFirst()
            }
            val closedQuantity = consumed.sumOf(ExecutionSlice::quantity)
            if (closedQuantity > EPSILON) {
                trades += completedTrade(consumed, ExecutionSlice(execution, closedQuantity), null)
            }
            if (sell.hasRemaining()) deferredSells.addLast(sell)
        }

        fun result(): BrokerTradeReconciliation {
            if (lots.isNotEmpty()) trades += openTrade(lots.toList())
            return BrokerTradeReconciliation(
                trades.sortedWith(compareBy(BrokerTrade::entryEpochSeconds, { it.exitEpochSeconds ?: Long.MAX_VALUE })),
                correctedSellIds.size,
                deferredSells.count(ExecutionRemainder::hasRemaining)
            )
        }

        private fun completedTrade(
            buys: List<ExecutionSlice>,
            sell: ExecutionSlice,
            entryEpochOverride: Long?
        ): BrokerTrade {
            val quantity = buys.sumOf(ExecutionSlice::quantity)
            val purchaseAmount = buys.sumOf(ExecutionSlice::grossAmount)
            val buyCharges = buys.sumOf(ExecutionSlice::charges)
            val proceeds = sell.grossAmount
            val sellCharges = sell.charges
            val profit = proceeds - sellCharges - purchaseAmount - buyCharges
            val costs = purchaseAmount + buyCharges
            val firstBuy = buys.minBy(ExecutionSlice::epochSeconds)
            return BrokerTrade(
                symbol, firstBuy.execution.isin ?: sell.execution.isin, quantity,
                entryEpochOverride ?: firstBuy.epochSeconds, purchaseAmount / quantity,
                sell.epochSeconds, proceeds / quantity, profit,
                if (costs == 0.0) null else profit / costs * 100.0,
                buyCharges + sellCharges, firstBuy.execution.currency
            )
        }

        private fun openTrade(openLots: List<ExecutionRemainder>): BrokerTrade {
            val slices = openLots.map { it.peek() }
            val quantity = slices.sumOf(ExecutionSlice::quantity)
            val purchaseAmount = slices.sumOf(ExecutionSlice::grossAmount)
            val charges = slices.sumOf(ExecutionSlice::charges)
            val first = slices.minBy(ExecutionSlice::epochSeconds)
            return BrokerTrade(
                symbol, first.execution.isin, quantity, first.epochSeconds,
                purchaseAmount / quantity, null, null, null, null, charges, first.execution.currency
            )
        }
    }

    private class ExecutionRemainder(val execution: BrokerExecution) {
        var remaining = execution.shares
            private set

        fun hasRemaining(): Boolean = remaining > EPSILON

        fun slice(quantity: Double): ExecutionSlice {
            consume(quantity)
            return ExecutionSlice(execution, quantity)
        }

        fun consume(quantity: Double) {
            require(quantity >= 0.0 && quantity <= remaining + EPSILON)
            remaining = (remaining - quantity).coerceAtLeast(0.0)
        }

        fun peek() = ExecutionSlice(execution, remaining)
    }

    private data class ExecutionSlice(val execution: BrokerExecution, val quantity: Double) {
        val epochSeconds: Long get() = execution.epochSeconds
        private val share: Double get() = quantity / execution.shares
        val grossAmount: Double get() = abs(execution.amount) * share
        val charges: Double get() = (execution.fee + execution.tax) * share
    }

    private fun sameQuantity(first: Double, second: Double): Boolean = abs(first - second) <= EPSILON
    private fun BrokerExecution.isBuy(): Boolean = type.equals("Buy", ignoreCase = true)
    private fun BrokerExecution.isSell(): Boolean = type.equals("Sell", ignoreCase = true)

    private fun normalize(value: String): String = value.lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .replace(Regex("\\b(inc|corp|corporation|company|co|plc|ag|se|nv|sa|ltd|the)\\b"), " ")
        .replace(Regex("\\s+"), " ").trim()

    private const val EPSILON = 0.000_000_1
    private const val CORRECTION_WINDOW_SECONDS = 4L * 24 * 60 * 60
}

internal data class BrokerTradeReconciliation(
    val trades: List<BrokerTrade>,
    val correctedOrder: Int,
    val unmatchedSells: Int
)

internal data class BrokerExecution(
    val id: Long,
    val epochSeconds: Long,
    val description: String,
    val type: String,
    val isin: String?,
    val shares: Double,
    val price: Double,
    val amount: Double,
    val fee: Double,
    val tax: Double,
    val currency: String,
    val linkedSymbol: String? = null
)
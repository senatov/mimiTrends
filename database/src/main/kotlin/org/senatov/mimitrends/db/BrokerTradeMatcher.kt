package org.senatov.mimitrends.db

import org.senatov.mimitrends.model.BrokerTrade

internal object BrokerTradeMatcher {
    fun matches(description: String, companyName: String): Boolean {
        val transactionName = normalize(description)
        val instrumentName = normalize(companyName)
        if (transactionName.isBlank() || instrumentName.isBlank()) return false
        return transactionName == instrumentName || transactionName.startsWith(instrumentName) ||
            instrumentName.startsWith(transactionName)
    }

    fun pair(symbol: String, executions: List<BrokerExecution>): List<BrokerTrade> {
        return reconcile(symbol, executions).trades
    }

    fun reconcile(symbol: String, executions: List<BrokerExecution>): BrokerTradeReconciliation {
        val result = mutableListOf<BrokerTrade>()
        var position = Position()
        val deferredSells = mutableListOf<BrokerExecution>()
        var correctedOrder = 0
        var unmatchedSells = 0
        executions.groupBy(BrokerExecution::epochSeconds).toSortedMap().forEach { (_, sameSecond) ->
            val pending = sameSecond.sortedBy(BrokerExecution::id).toMutableList()
            while (pending.isNotEmpty()) {
                val closingSell = pending.firstOrNull { it.isSell() && sameQuantity(it.shares, position.quantity) }
                if (!position.isEmpty && closingSell != null) {
                    pending.remove(closingSell)
                    position.add(closingSell)
                    result += position.completed(symbol)
                    position = Position()
                    continue
                }
                val sell = pending.firstOrNull { it.isSell() }
                // Broker exports do not preserve execution order inside the same second. Apply buys
                // before non-closing sells so a flat batch cannot manufacture a short and an open long.
                val buy = pending.firstOrNull { it.isBuy() }
                if (buy != null) {
                    pending.remove(buy)
                    val invertedSell = deferredSells.firstOrNull {
                        sameQuantity(it.shares, buy.shares) &&
                            buy.epochSeconds - it.epochSeconds in 0..CORRECTION_WINDOW_SECONDS
                    }
                    if (position.isEmpty && invertedSell != null) {
                        deferredSells.remove(invertedSell)
                        val correctedBuy = buy.copy(epochSeconds = invertedSell.epochSeconds)
                        position.add(correctedBuy)
                        position.add(invertedSell)
                        result += position.completed(symbol)
                        position = Position()
                        correctedOrder++
                        continue
                    }
                    position.add(buy)
                    continue
                }
                if (sell != null && sell.shares <= position.quantity + EPSILON) {
                    pending.remove(sell)
                    position.add(sell)
                    if (position.isEmpty) {
                        result += position.completed(symbol)
                        position = Position()
                    }
                    continue
                }
                pending.remove(requireNotNull(sell))
                deferredSells += sell
            }
        }
        unmatchedSells += deferredSells.size
        if (!position.isEmpty) result += position.open(symbol)
        return BrokerTradeReconciliation(result, correctedOrder, unmatchedSells)
    }

    private fun sameQuantity(first: Double, second: Double): Boolean = kotlin.math.abs(first - second) <= EPSILON

    private fun BrokerExecution.isBuy(): Boolean = type.equals("Buy", ignoreCase = true)
    private fun BrokerExecution.isSell(): Boolean = type.equals("Sell", ignoreCase = true)

    private class Position {
        private val buys = mutableListOf<BrokerExecution>()
        private val sells = mutableListOf<BrokerExecution>()
        var quantity = 0.0
            private set
        val isEmpty: Boolean get() = sameQuantity(quantity, 0.0)

        fun add(execution: BrokerExecution) {
            if (execution.isBuy()) {
                buys += execution
                quantity += execution.shares
            } else {
                sells += execution
                quantity -= execution.shares
            }
            if (sameQuantity(quantity, 0.0)) quantity = 0.0
        }

        fun completed(symbol: String): BrokerTrade {
            val firstBuy = buys.first()
            val lastSell = sells.last()
            val boughtQuantity = buys.sumOf(BrokerExecution::shares)
            val soldQuantity = sells.sumOf(BrokerExecution::shares)
            val purchaseAmount = -buys.sumOf(BrokerExecution::amount)
            val costs = purchaseAmount + buys.sumOf { it.fee + it.tax }
            val proceeds = sells.sumOf(BrokerExecution::amount) - sells.sumOf { it.fee + it.tax }
            val profit = proceeds - costs
            return BrokerTrade(symbol, firstBuy.isin ?: lastSell.isin, boughtQuantity,
                firstBuy.epochSeconds, purchaseAmount / boughtQuantity, lastSell.epochSeconds,
                proceeds / soldQuantity, profit, if (costs == 0.0) null else profit / costs * 100.0,
                buys.sumOf { it.fee + it.tax } + sells.sumOf { it.fee + it.tax }, firstBuy.currency)
        }

        fun open(symbol: String): BrokerTrade {
            val firstBuy = buys.first()
            val purchaseAmount = -buys.sumOf(BrokerExecution::amount)
            return BrokerTrade(symbol, firstBuy.isin, quantity, firstBuy.epochSeconds,
                purchaseAmount / buys.sumOf(BrokerExecution::shares), null, null, null, null,
                buys.sumOf { it.fee + it.tax } + sells.sumOf { it.fee + it.tax }, firstBuy.currency)
        }
    }

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

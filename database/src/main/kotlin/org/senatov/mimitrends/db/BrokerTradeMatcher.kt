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
        val result = mutableListOf<BrokerTrade>()
        var openBuy: BrokerExecution? = null
        executions.groupBy(BrokerExecution::epochSeconds).toSortedMap().forEach { (_, sameSecond) ->
            val pending = sameSecond.sortedBy(BrokerExecution::id).toMutableList()
            while (pending.isNotEmpty()) {
                if (openBuy == null) {
                    val buy = pending.firstOrNull { it.type.equals("Buy", ignoreCase = true) } ?: break
                    pending.remove(buy)
                    openBuy = buy
                } else {
                    val buy = requireNotNull(openBuy)
                    val sell = pending.filter { it.type.equals("Sell", ignoreCase = true) }
                        .minByOrNull { kotlin.math.abs(it.shares - buy.shares) } ?: break
                    pending.remove(sell)
                    if (sameQuantity(buy, sell)) {
                        result += completed(symbol, buy, sell)
                        openBuy = null
                    } else {
                        break
                    }
                }
            }
        }
        openBuy?.let { buy ->
            result += BrokerTrade(symbol, buy.isin, buy.shares, buy.epochSeconds, buy.price,
                null, null, null, null, buy.fee + buy.tax, buy.currency)
        }
        return result
    }

    private fun completed(symbol: String, buy: BrokerExecution, sell: BrokerExecution): BrokerTrade {
        val costs = -buy.amount + buy.fee + buy.tax
        val proceeds = sell.amount - sell.fee - sell.tax
        val profit = proceeds - costs
        return BrokerTrade(symbol, buy.isin ?: sell.isin, buy.shares, buy.epochSeconds, buy.price,
            sell.epochSeconds, sell.price, profit, if (costs == 0.0) null else profit / costs * 100.0,
            buy.fee + buy.tax + sell.fee + sell.tax, buy.currency)
    }

    private fun sameQuantity(buy: BrokerExecution, sell: BrokerExecution): Boolean =
        kotlin.math.abs(buy.shares - sell.shares) <= EPSILON

    private fun normalize(value: String): String = value.lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .replace(Regex("\\b(inc|corp|corporation|company|co|plc|ag|se|nv|sa|ltd|the)\\b"), " ")
        .replace(Regex("\\s+"), " ").trim()

    private const val EPSILON = 0.000_000_1
}

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
    val currency: String
)

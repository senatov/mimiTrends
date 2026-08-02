package org.senatov.mimitrends.db

import org.senatov.mimitrends.model.BrokerTrade
import java.util.ArrayDeque

internal object BrokerTradeMatcher {
    fun matches(description: String, companyName: String): Boolean {
        val transactionName = normalize(description)
        val instrumentName = normalize(companyName)
        if (transactionName.isBlank() || instrumentName.isBlank()) return false
        return transactionName == instrumentName || transactionName.startsWith(instrumentName) ||
            instrumentName.startsWith(transactionName)
    }

    fun pair(symbol: String, executions: List<BrokerExecution>): List<BrokerTrade> {
        val buys = ArrayDeque<OpenLot>()
        val result = mutableListOf<BrokerTrade>()
        executions.sortedWith(compareBy<BrokerExecution> { it.epochSeconds }.thenBy { it.id }).forEach { execution ->
            when (execution.type.lowercase()) {
                "buy" -> buys += OpenLot(execution, execution.shares)
                "sell" -> {
                    var remaining = execution.shares
                    while (remaining > EPSILON && buys.isNotEmpty()) {
                        val lot = buys.first()
                        val quantity = minOf(remaining, lot.remaining)
                        result += completed(symbol, lot.execution, execution, quantity)
                        lot.remaining -= quantity
                        remaining -= quantity
                        if (lot.remaining <= EPSILON) buys.removeFirst()
                    }
                }
            }
        }
        buys.forEach { lot ->
            val buy = lot.execution
            val allocation = lot.remaining / buy.shares
            result += BrokerTrade(symbol, buy.isin, lot.remaining, buy.epochSeconds, buy.price,
                null, null, null, null, (buy.fee + buy.tax) * allocation, buy.currency)
        }
        return result.sortedBy(BrokerTrade::entryEpochSeconds)
    }

    private fun completed(
        symbol: String,
        buy: BrokerExecution,
        sell: BrokerExecution,
        quantity: Double
    ): BrokerTrade {
        val buyAllocation = quantity / buy.shares
        val sellAllocation = quantity / sell.shares
        val costs = -buy.amount * buyAllocation + (buy.fee + buy.tax) * buyAllocation
        val proceeds = sell.amount * sellAllocation - (sell.fee + sell.tax) * sellAllocation
        val profit = proceeds - costs
        return BrokerTrade(symbol, buy.isin ?: sell.isin, quantity, buy.epochSeconds, buy.price,
            sell.epochSeconds, sell.price, profit, if (costs == 0.0) null else profit / costs * 100.0,
            (buy.fee + buy.tax) * buyAllocation + (sell.fee + sell.tax) * sellAllocation, buy.currency)
    }

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

private data class OpenLot(val execution: BrokerExecution, var remaining: Double)

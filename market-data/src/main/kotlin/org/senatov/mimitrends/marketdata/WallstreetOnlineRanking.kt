package org.senatov.mimitrends.marketdata

enum class WallstreetOnlineCategory(val label: String) {
    TOP("Top"),
    FLOP("Flop"),
    MOST_TRADED("Most traded"),
    GAP_UP("Gap up"),
    GAP_DOWN("Gap down"),
    REVERSAL_UP("Reversal up"),
    REVERSAL_DOWN("Reversal down"),
    HIGH_RANGE("High range")
}

data class WallstreetOnlineRankedMover(
    val mover: WallstreetOnlineMover,
    val category: WallstreetOnlineCategory,
    val rank: Int
)

internal data class WallstreetOnlineRankingPage(
    val path: String,
    val category: WallstreetOnlineCategory
)

internal object WallstreetOnlineRankingPages {
    val entries = listOf(
        WallstreetOnlineRankingPage("/statistik/top-aktien-performance", WallstreetOnlineCategory.TOP),
        WallstreetOnlineRankingPage("/statistik/top-aktien-meistgehandelt", WallstreetOnlineCategory.MOST_TRADED),
        WallstreetOnlineRankingPage("/statistik/flop-aktien-performance", WallstreetOnlineCategory.FLOP),
        WallstreetOnlineRankingPage("/statistik/top-50-deutsche-aktien", WallstreetOnlineCategory.TOP),
        WallstreetOnlineRankingPage("/statistik/top-50-us-aktien", WallstreetOnlineCategory.TOP),
        WallstreetOnlineRankingPage("/statistik/top-cdax-aktien-performance", WallstreetOnlineCategory.TOP),
        WallstreetOnlineRankingPage("/statistik/top-cdax-aktien-meistgehandelt", WallstreetOnlineCategory.MOST_TRADED),
        WallstreetOnlineRankingPage("/statistik/top-nasdaq100-aktien-performance", WallstreetOnlineCategory.TOP),
        WallstreetOnlineRankingPage("/statistik/top-nasdaq100-aktien-meistgehandelt", WallstreetOnlineCategory.MOST_TRADED),
        WallstreetOnlineRankingPage("/statistik/top-sp500-aktien-performance", WallstreetOnlineCategory.TOP),
        WallstreetOnlineRankingPage("/statistik/top-sp500-aktien-meistgehandelt", WallstreetOnlineCategory.MOST_TRADED),
        WallstreetOnlineRankingPage("/statistik/top-eurostoxx-aktien-performance", WallstreetOnlineCategory.TOP),
        WallstreetOnlineRankingPage("/statistik/top-eurostoxx-aktien-meistgehandelt", WallstreetOnlineCategory.MOST_TRADED),
        WallstreetOnlineRankingPage("/statistik/gap-up-aktien", WallstreetOnlineCategory.GAP_UP),
        WallstreetOnlineRankingPage("/statistik/gap-down-aktien", WallstreetOnlineCategory.GAP_DOWN),
        WallstreetOnlineRankingPage("/statistik/reversal-up-aktien", WallstreetOnlineCategory.REVERSAL_UP),
        WallstreetOnlineRankingPage("/statistik/reversal-down-aktien", WallstreetOnlineCategory.REVERSAL_DOWN),
        WallstreetOnlineRankingPage("/statistik/high-range-aktien", WallstreetOnlineCategory.HIGH_RANGE)
    )
}
# Financial and statistical methods

MiMiTrends separates published statistical methods from application-specific ranking heuristics. A citation means
that the referenced mathematical method is implemented; it does not imply endorsement of MiMiTrends by the author.

| Method | Implementation | Reference |
|---|---|---|
| Median absolute deviation and scaled robust dispersion | `ScannerEngine.robustScale`, historical baselines | NIST, *Median Absolute Deviation*, scale factor `1 / Φ⁻¹(0.75) ≈ 1.4826`: https://itl.nist.gov/div898/software/dataplot/refman2/auxillar/mad.htm |
| Ordinary least-squares slope and coefficient of determination | `SteadyRiseDetector`, `MultiSessionRiseDetector`, `EntryTimingClassifier` | NIST Engineering Statistics Handbook, *Linear Least Squares Regression*: https://www.itl.nist.gov/div898/handbook/pmd/section1/pmd141.htm |
| Volume-weighted average price | `ResearchFeatureExtractor`, `LongCandidateSafetyFilter` | Ananth Madhavan, *VWAP Strategies* (2002): https://www.pm-research.com/content/iijtrade/2002/1/32 |
| Exponentially weighted volatility | `MultiHorizonTrendModel` | J.P. Morgan/Reuters, *RiskMetrics Technical Document*, 4th ed. (1996): https://www.msci.com/documents/10199/5915b101-4206-4ba0-aee2-3449d5c7e95a |
| Binomial logistic regression | `LogisticPredictionModel` | Joseph Berkson, *Application of the Logistic Function to Bio-Assay* (1944): https://doi.org/10.1080/01621459.1944.10500699 |
| Probability forecast validation | `PredictiveModelStore.brier` | Glenn W. Brier, *Verification of Forecasts Expressed in Terms of Probability* (1950): https://doi.org/10.1175/1520-0493(1950)078%3C0001:VOFEIT%3E2.0.CO;2 |
| Binomial confidence interval | `SignalCalibrationStore.wilsonInterval` | Edwin B. Wilson, *Probable Inference, the Law of Succession, and Statistical Inference* (1927): https://doi.org/10.1080/01621459.1927.10502953 |

## Application-specific heuristics

The anomaly thresholds, entry-quality weights, safety weights, freshness penalties, universe-rank weighting,
relaxation levels, and signal-retention periods are MiMiTrends engineering heuristics. They are validated through
point-in-time samples and walk-forward outcomes but are not copied from, or claimed to be proven by, the references
above. Every predictive-model feature change increments `FEATURE_VERSION`, preventing incompatible stored weights
from being applied silently.

The dynamic universe uses current S&P 500 and Euro Stoxx 50 performance and turnover rankings from
wallstreetONLINE, interleaves both rankings, and fills missing positions from the configured large-cap universe.
Index membership supplies the capitalization/liquidity constraint; daily performance and turnover supply the
activity ordering.

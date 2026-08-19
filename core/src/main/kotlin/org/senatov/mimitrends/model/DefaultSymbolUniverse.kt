package org.senatov.mimitrends.model

object DefaultSymbolUniverse {
    val symbols: List<String> = FinancialTransactionTaxExclusions.removeFrom(listOf(
        "AAPL", "MSFT", "NVDA", "AMZN", "META", "GOOGL", "GOOG", "TSLA",
        "AVGO", "BRK-B", "JPM", "V", "MA", "WMT", "LLY", "NFLX",
        "XOM", "COST", "JNJ", "HD", "PG", "BAC", "ABBV", "KO",
        "PM", "CRM", "ORCL", "CSCO", "CVX", "IBM", "GE", "CAT",
        "MRK", "TMO", "ABT", "MCD", "ACN", "GS", "LIN", "ISRG",
        "DIS", "AMD", "QCOM", "TXN", "AMAT", "INTU", "AMGN", "NOW",
        "BKNG", "RTX", "PEP", "SPGI", "DHR", "HON", "PFE", "CMCSA",
        "UNP", "LOW", "NEE", "UPS", "T", "VZ", "BA", "SBUX",
        "MDT", "COP", "BMY", "DE", "ADP", "PLD", "GILD", "PANW",
        "SCHW", "C", "UBER", "AXP", "SYK", "TJX", "BLK", "VRTX",
        "MRSH", "ETN", "LRCX", "CB", "ADI", "PGR", "MU", "BSX",
        "KLAC", "FISV", "ELV", "REGN", "CME", "SO", "DUK", "MO",
        "ICE", "SHW", "WM", "CL", "EOG", "PH", "NOC", "ITW",
        "MCO", "APH", "GD", "CDNS", "SNPS", "MAR", "ORLY", "MSI",
        "CTAS", "USB", "AJG", "FCX", "EMR", "FDX", "HCA", "MMM",
        "APO", "WELL", "TGT", "NKE", "GM", "F", "PYPL", "ABNB",
        "COIN", "PLTR", "SHOP", "SOFI", "SNAP", "INTC", "XYZ", "CRWD",
        "DDOG", "NET", "ZS", "TEAM", "MDB", "SNOW", "ARM", "SMCI",
        "RBLX", "HOOD", "DKNG", "MSTR", "RIVN", "MS", "NIO", "BABA",
        "PDD", "JD", "TSM", "ASML", "NVO", "SAP", "SONY", "TM",

        "SAP.DE", "SIE.DE", "ALV.DE", "DTE.DE", "BMW.DE", "MBG.DE", "BAS.DE", "RWE.DE",
        "DBK.DE", "DHL.DE", "ADS.DE", "IFX.DE", "VOW3.DE", "HEN3.DE", "BEI.DE", "BAYN.DE",
        "MTX.DE", "ZAL.DE", "FRE.DE", "EOAN.DE", "VNA.DE", "CON.DE", "HEI.DE", "QIA.DE",
        "MUV2.DE", "DB1.DE", "MRK.DE", "SY1.DE", "PAH3.DE", "P911.DE", "AIR.DE", "ENR.DE",
        "ASML.AS", "INGA.AS", "AD.AS", "UNA.AS", "PHIA.AS", "ASM.AS", "HEIA.AS", "KPN.AS",
        "PRX.AS", "AKZA.AS", "WKL.AS", "NN.AS", "AGN.AS", "RAND.AS", "IMCD.AS", "BESI.AS",
        "MC.PA", "OR.PA", "TTE.PA", "AIR.PA", "BNP.PA", "SAN.PA", "SU.PA", "RMS.PA",
        "DG.PA", "CS.PA", "RI.PA", "ACA.PA", "STMPA.PA", "VIE.PA", "KER.PA", "CAP.PA",
        "ORA.PA", "GLE.PA", "ENGI.PA", "DSY.PA", "HO.PA", "PUB.PA", "ML.PA", "CA.PA",
        "RHM.DE", "CBK.DE", "SHELL.AS", "MT.AS", "SGO.PA", "LR.PA", "STLAP.PA"
    ))
}

package org.senatov.mimitrends.db

import java.nio.file.Files
import java.time.ZoneId
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class ScalableCsvImporterTest {
    @Test fun `filters cancellations and parses scalable number and quoted text formats`() {
        val csv = Files.createTempFile("scalable-transactions", ".csv")
        try {
            csv.writeText(
                "date;time;status;reference;description;assetType;type;isin;shares;price;amount;fee;tax;currency\n" +
                    "2026-08-03;21:22:05;Executed;buy-220;SoFi Technologies;Security;Buy;US83406F1021;220;15,546;-3.420,12;0,00;0,00;EUR\n" +
                    "2026-08-03;21:43:14;Cancelled;cancelled;SoFi Technologies;Security;Sell;US83406F1021;220;15,500;3.410,00;0,00;0,00;EUR\n" +
                    "2026-08-03;21:43:14;Executed;buy-5;\"SoFi; Technologies\";Security;Buy;US83406F1021;5;15,594;-77,97;0,99;0,00;EUR\n" +
                    "2026-08-03;21:43:14;Executed;sell-225;SoFi Technologies;Security;Sell;US83406F1021;225;15,508;3.489,30;0,00;0,00;EUR\n"
            )

            val transactions = ScalableCsvImporter.parse(csv, ZoneId.of("Europe/Berlin"))

            assertEquals(listOf("buy-220", "buy-5", "sell-225"), transactions.map { it.reference })
            assertEquals(-3420.12, transactions.first().amount, 0.000_001)
            assertEquals("SoFi; Technologies", transactions[1].description)
            assertEquals(0.99, transactions[1].fee, 0.000_001)
        } finally {
            Files.deleteIfExists(csv)
        }
    }
}

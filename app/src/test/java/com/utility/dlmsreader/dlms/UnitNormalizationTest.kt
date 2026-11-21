package com.utility.dlmsreader.dlms

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit normalization is the single funnel that turns base scaler units from
 * live readings, `.OUT` / `.dat` / `.csv` imports and saved reports into the
 * report units. These tests pin both directions: base units are divided by
 * 1000 once, and already-prefixed units are never touched again.
 */
class UnitNormalizationTest {

    private fun quantity(value: String, unit: String): NormalizedQuantity =
        UnitNormalization.normalize(value, unit)

    @Test
    fun `wh becomes kWh with strict three decimals`() {
        assertEquals(
            NormalizedQuantity("7,443.000", "kWh"),
            quantity("7443000", "WH")
        )
        assertEquals(NormalizedQuantity("7,443.000", "kWh"), quantity("7443000", "Wh"))
        assertEquals(NormalizedQuantity("7,443.000", "kWh"), quantity("7443000", "wh"))
    }

    @Test
    fun `vah becomes kVAh`() {
        assertEquals(
            NormalizedQuantity("9,077.800", "kVAh"),
            quantity("9077800", "VAH")
        )
        assertEquals(NormalizedQuantity("9,077.800", "kVAh"), quantity("9077800", "vah"))
    }

    @Test
    fun `varh becomes kVArh`() {
        assertEquals(NormalizedQuantity("1,234.567", "kVArh"), quantity("1234567", "varh"))
        assertEquals(NormalizedQuantity("1,234.567", "kVArh"), quantity("1234567", "VARH"))
    }

    @Test
    fun `w becomes kW`() {
        assertEquals(NormalizedQuantity("1.850", "kW"), quantity("1850", "W"))
        assertEquals(NormalizedQuantity("0.000", "kW"), quantity("0", "W"))
    }

    @Test
    fun `va becomes kVA`() {
        assertEquals(NormalizedQuantity("3.680", "kVA"), quantity("3680", "VA"))
    }

    @Test
    fun `var becomes kVAr`() {
        assertEquals(NormalizedQuantity("0.500", "kVAr"), quantity("500", "var"))
        assertEquals(NormalizedQuantity("0.500", "kVAr"), quantity("500", "VAR"))
    }

    @Test
    fun `already kilo units are never divided twice`() {
        val untouched = listOf(
            "7,443.000" to "kWh",
            "9,077.800" to "kVAh",
            "1.850" to "kW",
            "3.680" to "kVA",
            "1,234.567" to "kVArh",
            "0.500" to "kVAr",
            "2.500" to "kwh",
            "1.850" to "KW"
        )
        untouched.forEach { (value, unit) ->
            assertEquals(
                "value/unit changed for $value $unit",
                NormalizedQuantity(value, unit),
                quantity(value, unit)
            )
        }
        // Mega prefixes are left alone too.
        assertEquals(NormalizedQuantity("2.500", "MWh"), quantity("2.500", "MWh"))
    }

    @Test
    fun `unit embedded in the exported value is honoured`() {
        assertEquals(NormalizedQuantity("7,443.000", "kWh"), quantity("7443000 WH", ""))
        assertEquals(NormalizedQuantity("1.850", "kW"), quantity("1850 W", ""))
        assertEquals(NormalizedQuantity("3.680", "kVA"), quantity("3680 VA", ""))
        assertEquals(NormalizedQuantity("9,077.800", "kVAh"), quantity("9077800 VAH", ""))
    }

    @Test
    fun `placeholders timestamps and free text stay untouched`() {
        assertEquals(NormalizedQuantity("N/A", "WH"), quantity("N/A", "WH"))
        assertEquals(NormalizedQuantity("-", "W"), quantity("-", "W"))
        assertEquals(NormalizedQuantity("", "WH"), quantity("", "WH"))
        assertEquals(
            NormalizedQuantity("28-12-23 00:15", ""),
            quantity("28-12-23 00:15", "")
        )
        assertEquals(NormalizedQuantity("abc", "kW"), quantity("abc", "kW"))
    }

    @Test
    fun `registers normalize value and unit together`() {
        val normalized = UnitNormalization.normalize(
            RegisterReading(
                "Cumulative Active Energy - Export",
                "1.0.2.8.0.255",
                "7443000",
                "WH"
            )
        )
        assertEquals("7,443.000", normalized.reading)
        assertEquals("kWh", normalized.unit)

        val alreadyKilo = RegisterReading(
            "Cumulative Active Energy - Import",
            "1.0.1.8.0.255",
            "7,443.000",
            "kWh"
        )
        assertEquals(alreadyKilo, UnitNormalization.normalize(alreadyKilo))
    }

    @Test
    fun `profile columns normalize imported base units and keep kilo columns intact`() {
        val section = ProfileSection(
            title = "Billing History Profile",
            profileObis = DlmsCosemCatalog.OBIS_BILLING_PROFILE,
            columns = listOf(
                ProfileColumn("Billing Date", "0.0.0.1.2.255", attributeIndex = 5),
                ProfileColumn("Block Energy - Active Export", "1.0.2.29.0.255", "WH"),
                ProfileColumn("Cumulative Active Energy - Import", "1.0.1.8.0.255", "kWh")
            ),
            rows = listOf(listOf("01-10-26 00:00", "7443000", "7,443.000"))
        )

        val normalized = UnitNormalization.normalize(section)

        assertEquals("kWh", normalized.columns[1].unit)
        assertEquals("kWh", normalized.columns[2].unit)
        assertEquals(
            listOf("01-10-26 00:00", "7,443.000", "7,443.000"),
            normalized.rows.single()
        )
    }

    @Test
    fun `report normalization covers registers identity and profiles`() {
        val report = MeterDataReport(
            identity = IdentitySection(
                rows = listOf(
                    ParameterRow("Maximum Demand", "1.0.2.6.0.255", "1850", "W"),
                    ParameterRow("Meter Serial Number", "0.0.96.1.0.255", "12345", "")
                )
            ),
            cumulativeEnergies = listOf(
                RegisterReading("Cumulative Active Energy - Export", "1.0.2.8.0.255", "7443000", "WH"),
                RegisterReading("Cumulative Active Energy - Import", "1.0.1.8.0.255", "7430000", "WH")
            ),
            instantaneous = ProfileSection(
                title = "Instantaneous Parameters Snapshot",
                profileObis = DlmsCosemCatalog.OBIS_INSTANTANEOUS_PROFILE,
                columns = listOf(ProfileColumn("Apparent Power", "1.0.9.7.0.255", "VA")),
                rows = listOf(listOf("3680"))
            )
        )

        val normalized = UnitNormalization.normalize(report)

        assertEquals("1.850", normalized.identity?.rows?.first()?.formattedValue)
        assertEquals("kW", normalized.identity?.rows?.first()?.unit)
        assertEquals("12345", normalized.identity?.rows?.last()?.formattedValue)
        assertEquals("7,443.000", normalized.cumulativeEnergies[0].reading)
        assertEquals("kWh", normalized.cumulativeEnergies[0].unit)
        assertEquals("3.680", normalized.instantaneous?.rows?.single()?.single())
        assertEquals("kVA", normalized.instantaneous?.columns?.single()?.unit)

        // Running the normalizer again must not change anything.
        assertEquals(normalized, UnitNormalization.normalize(normalized))
    }
}

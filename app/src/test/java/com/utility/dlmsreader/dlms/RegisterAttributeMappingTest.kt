package com.utility.dlmsreader.dlms

import gurux.dlms.GXDLMSClient
import gurux.dlms.GXDateTime
import gurux.dlms.GXStructure
import gurux.dlms.enums.Unit
import gurux.dlms.objects.GXDLMSDemandRegister
import gurux.dlms.objects.GXDLMSExtendedRegister
import gurux.dlms.objects.GXDLMSObject
import gurux.dlms.objects.GXDLMSRegister
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the register attribute indices against the DLMS UA Blue Book and the
 * Gurux object model. Class 5 (demand register) was previously read with the
 * class 4 layout shifted by one: attribute 1 (logical_name) as the value,
 * attribute 3 (last_average_value) as scaler_unit and attribute 4 as
 * capture_time.
 */
class RegisterAttributeMappingTest {

    private val client = GXDLMSClient()

    private fun newObject(kind: RegisterObjectKind, obis: String): GXDLMSObject = when (kind) {
        RegisterObjectKind.EXTENDED -> GXDLMSExtendedRegister(obis)
        RegisterObjectKind.DEMAND -> GXDLMSDemandRegister(obis)
        RegisterObjectKind.PLAIN -> GXDLMSRegister(obis)
    }

    private fun scalerUnit(scaler: Int, unit: Unit): GXStructure =
        GXStructure().apply {
            add(scaler.toByte())
            add(unit.value)
        }

    @Test
    fun `class ids match the Gurux object types`() {
        RegisterObjectKind.values().forEach { kind ->
            assertEquals(
                "class id for $kind",
                kind.classId,
                newObject(kind, "1.0.1.6.0.255").objectType.value
            )
        }
    }

    @Test
    fun `blue book attribute indices`() {
        assertEquals(2, RegisterObjectKind.EXTENDED.valueAttribute)
        assertEquals(3, RegisterObjectKind.EXTENDED.scalerUnitAttribute)
        assertEquals(5, RegisterObjectKind.EXTENDED.captureTimeAttribute)

        assertEquals(2, RegisterObjectKind.DEMAND.valueAttribute)
        assertEquals(4, RegisterObjectKind.DEMAND.scalerUnitAttribute)
        assertEquals(6, RegisterObjectKind.DEMAND.captureTimeAttribute)

        assertEquals(2, RegisterObjectKind.PLAIN.valueAttribute)
        assertEquals(3, RegisterObjectKind.PLAIN.scalerUnitAttribute)
        assertNull(RegisterObjectKind.PLAIN.captureTimeAttribute)
    }

    @Test
    fun `demand register attributes populate scaler unit value and capture time`() {
        val kind = RegisterObjectKind.DEMAND
        val register = GXDLMSDemandRegister("1.0.1.6.0.255")

        client.updateValue(register, kind.scalerUnitAttribute, scalerUnit(-3, Unit.ACTIVE_POWER))
        client.updateValue(register, kind.valueAttribute, 4250)
        client.updateValue(register, kind.captureTimeAttribute!!, GXDateTime(java.util.Date(1_795_684_500_000L)))

        assertEquals(0.001, register.scaler, 1e-9)
        assertEquals(Unit.ACTIVE_POWER, register.unit)
        assertEquals(4.25, (register.currentAverageValue as Number).toDouble(), 1e-9)
        assertNotNull(register.captureTime)
    }

    @Test
    fun `extended register attributes populate scaler unit value and capture time`() {
        val kind = RegisterObjectKind.EXTENDED
        val register = GXDLMSExtendedRegister("1.0.1.6.0.255")

        client.updateValue(register, kind.scalerUnitAttribute, scalerUnit(-3, Unit.ACTIVE_POWER))
        client.updateValue(register, kind.valueAttribute, 4250)
        client.updateValue(register, kind.captureTimeAttribute!!, GXDateTime(java.util.Date(1_795_684_500_000L)))

        assertEquals(0.001, register.scaler, 1e-9)
        assertEquals(Unit.ACTIVE_POWER, register.unit)
        assertEquals(4.25, (register.value as Number).toDouble(), 1e-9)
        assertNotNull(register.captureTime)
    }
}

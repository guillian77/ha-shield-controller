package com.dekadmin.hastield.ha

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityKindTest {

    @Test
    fun `reconnaît les domaines gérés`() {
        assertEquals(EntityKind.LIGHT, EntityKind.of("light.salon"))
        assertEquals(EntityKind.THERMOSTAT, EntityKind.of("climate.bureau"))
        assertEquals(EntityKind.SCRIPT, EntityKind.of("script.bonne_nuit"))
    }

    @Test
    fun `ignore les domaines non gérés`() {
        assertNull(EntityKind.of("sensor.temperature"))
        assertNull(EntityKind.of("media_player.tv"))
        assertFalse(EntityKind.isSupported("automation.test"))
    }

    @Test
    fun `ne confond pas un préfixe avec un domaine`() {
        assertNull(EntityKind.of("lightbulb.salon"))
        assertNull(EntityKind.of("scripts.divers"))
        assertTrue(EntityKind.isSupported("script.tout_eteindre"))
    }
}

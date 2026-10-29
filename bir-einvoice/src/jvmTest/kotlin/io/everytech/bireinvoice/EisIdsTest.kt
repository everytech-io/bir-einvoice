package io.everytech.bireinvoice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EisIdsTest {
    @Test
    fun eisUniqueIdFormat() {
        val pattern = Regex("2026102920AB12CD[0-9A-Z]{8}")
        assertTrue(pattern.matches(EisIds.eisUniqueId("2026-10-29", "20AB12CD")))
        assertTrue(pattern.matches(EisIds.eisUniqueId("20261029", "20AB12CD")))
        assertEquals(200, List(200) { EisIds.eisUniqueId("20261029", "20AB12CD") }.toSet().size)
    }

    @Test
    fun eisUniqueIdRejectsBadInput() {
        assertFailsWith<IllegalArgumentException> { EisIds.eisUniqueId("2026-10", "20AB12CD") }
        assertFailsWith<IllegalArgumentException> { EisIds.eisUniqueId("2026-10-29", "20AB12C") }
        assertFailsWith<IllegalArgumentException> { EisIds.eisUniqueId("2026-10-29", "20AB-2CD") }
    }

    @Test
    fun submitIdFormat() {
        val pattern = Regex("20AB12CD-20261029-[0-9a-f]{12}")
        assertTrue(pattern.matches(EisIds.submitId("20AB12CD", "20261029")))
        assertTrue(pattern.matches(EisIds.submitId("20AB12CD", "2026-10-29")))
    }

    @Test
    fun generatedIdsPassTheValidator() {
        val uid = EisIds.eisUniqueId("2026-10-29", "20AB12CD")
        assertEquals(emptyList(), CasValidator.validate(EisJson.encode(Samples.vatable(uid))))
    }
}

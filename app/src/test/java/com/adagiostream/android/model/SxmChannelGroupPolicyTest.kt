package com.adagiostream.android.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SxmChannelGroupPolicyTest {
    @Test
    fun `legacy migration selects every raw name admitted by the old matcher`() {
        val names = setOf(
            "SiriusXM",
            "sxm",
            "sirius",
            "sirius xm",
            "XM",
            "Provider SiriusXM Music",
            "General",
            "SiriusXMusic",
        )

        assertEquals(
            setOf("SiriusXM", "sxm", "sirius", "sirius xm", "XM", "Provider SiriusXM Music"),
            SxmChannelGroupPolicy.legacySelection(names),
        )
    }

    @Test
    fun `migration is a no-op after selection has initialized even when empty`() {
        assertEquals(
            emptySet<String>(),
            SxmChannelGroupPolicy.migrateIfNeeded(emptySet(), setOf("SiriusXM")),
        )
        assertEquals(
            setOf("Chosen"),
            SxmChannelGroupPolicy.migrateIfNeeded(setOf("Chosen"), setOf("SiriusXM")),
        )
    }

    @Test
    fun `migration waits while inventory is not complete`() {
        assertEquals(
            null,
            SxmChannelGroupPolicy.migrateIfNeeded(null, null),
        )
    }
}

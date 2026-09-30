package com.rubicon.analytics.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BuildInfoTest {
    @Test
    fun `version is generated from the version Gradle property`() {
        assertEquals(System.getProperty("rubicon.expectedVersion"), BuildInfo.VERSION)
    }
}

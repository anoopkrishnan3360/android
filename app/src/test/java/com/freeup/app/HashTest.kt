package com.freeup.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HashTest {
    @Test fun knownVector() =
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256("abc".byteInputStream()))
    @Test fun differentInputDiffers() =
        assertNotEquals(sha256("abc".byteInputStream()), sha256("abd".byteInputStream()))
}

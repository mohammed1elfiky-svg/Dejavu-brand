package com.dejavu.core.data

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ValidatorsTest {
    @Test
    fun `accepts Egyptian mobile numbers in local and international form`() {
        assertNull(Validators.phone("01012345678"))
        assertNull(Validators.phone("+201112345678"))
        assertNull(Validators.phone("0155 1234567"))
        assertNotNull(Validators.phone("0131234567"))
        assertNotNull(Validators.phone("12345"))
    }

    @Test
    fun `accepts Yemeni and Saudi mobile numbers`() {
        assertNull(Validators.phone("771234567"))
        assertNull(Validators.phone("+967 733 123 456"))
        assertNull(Validators.phone("00967781234567"))
        assertNull(Validators.phone("701234567"))
        assertNotNull(Validators.phone("721234567"))
        assertNotNull(Validators.phone("77123456"))
        assertNull(Validators.phone("0501234567"))
        assertNull(Validators.phone("+966501234567"))
        assertNotNull(Validators.phone("+9660501234567"))
        assertNotNull(Validators.phone("0401234567"))
    }

    @Test
    fun `checks password, name and optional email`() {
        assertNotNull(Validators.password("short"))
        assertNull(Validators.password("long-enough"))
        assertNotNull(Validators.name(" "))
        assertNull(Validators.email(""))
        assertNotNull(Validators.email("not-an-email"))
    }
}

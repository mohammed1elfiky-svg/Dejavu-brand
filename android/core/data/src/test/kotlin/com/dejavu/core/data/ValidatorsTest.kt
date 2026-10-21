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
    fun `checks password, name and optional email`() {
        assertNotNull(Validators.password("short"))
        assertNull(Validators.password("long-enough"))
        assertNotNull(Validators.name(" "))
        assertNull(Validators.email(""))
        assertNotNull(Validators.email("not-an-email"))
    }
}

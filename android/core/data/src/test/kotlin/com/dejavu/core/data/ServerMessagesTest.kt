package com.dejavu.core.data

import com.dejavu.core.network.ApiException
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerMessagesTest {
    @Test
    fun `translates exact and templated messages`() {
        assertEquals("رقم الموبايل أو كلمة المرور غير صحيحة", ServerMessages.translate("Invalid credentials"))
        assertEquals("متبقي 3 فقط", ServerMessages.translate("Only 3 left in stock"))
        assertEquals("التوصيل إلى أسوان غير متاح حالياً", ServerMessages.translate("Delivery to أسوان is not available yet"))
        assertEquals("DJV-SS-ONE-1: نفدت الكمية", ServerMessages.translate("DJV-SS-ONE-1: Out of stock"))
    }

    @Test
    fun `keeps unknown messages and translates each validation line`() {
        assertEquals("Something new", ServerMessages.translate("Something new"))
        val e = ApiException(400, "Coupon has expired\nOnly 1 left in stock")
        assertEquals("انتهت صلاحية كود الخصم\nمتبقي 1 فقط", e.userMessage())
    }
}

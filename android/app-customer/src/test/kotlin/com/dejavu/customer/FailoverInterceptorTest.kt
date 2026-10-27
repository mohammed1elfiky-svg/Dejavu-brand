package com.dejavu.customer

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class FailoverInterceptorTest {
    @Test
    fun movesTheRequestToTheNewServerKeepingPathAndQuery() {
        val moved = FailoverInterceptor.moveTo(
            "https://dejavu.zapto.org/api/v1/store/products?page=2&q=%D9%83%D8%B9%D8%A8".toHttpUrl(),
            from = "https://dejavu.zapto.org/api/v1/",
            to = "https://shop.example.com/api/v1/",
        )
        assertEquals("https://shop.example.com/api/v1/store/products?page=2&q=%D9%83%D8%B9%D8%A8", moved.toString())
    }
}

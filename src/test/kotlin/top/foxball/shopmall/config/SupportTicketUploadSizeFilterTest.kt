package top.foxball.shopmall.config

import jakarta.servlet.FilterChain
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SupportTicketUploadSizeFilterTest {
    private val properties = SupportTicketProperties(
        maxAttachmentBytesPerMessage = 8,
        maxMessageRequestBytes = 10,
    )
    private val filter = SupportTicketUploadSizeFilter(properties)

    @Test
    fun `oversized customer message request is rejected before multipart parsing`() {
        val request = messageRequest("/api/support-tickets/3/messages", 11)
        val response = MockHttpServletResponse()
        val chain = mock(FilterChain::class.java)

        filter.doFilter(request, response, chain)

        assertEquals(413, response.status)
        assertEquals("application/json;charset=UTF-8", response.contentType)
        assertTrue(response.contentAsString.contains("10 字节"))
        verifyNoInteractions(chain)
    }

    @Test
    fun `oversized administrator message request is rejected`() {
        val request = messageRequest("/admin/api/support-tickets/3/messages", 11)
        val response = MockHttpServletResponse()
        val chain = mock(FilterChain::class.java)

        filter.doFilter(request, response, chain)

        assertEquals(413, response.status)
        verifyNoInteractions(chain)
    }

    @Test
    fun `message request within limit continues through filter chain`() {
        val request = messageRequest("/api/support-tickets/3/messages", 10)
        val response = MockHttpServletResponse()
        val chain = mock(FilterChain::class.java)

        filter.doFilter(request, response, chain)

        verify(chain).doFilter(request, response)
    }

    @Test
    fun `non message route is not subject to ticket upload limit`() {
        val request = messageRequest("/api/files", 11)
        val response = MockHttpServletResponse()
        val chain = mock(FilterChain::class.java)

        filter.doFilter(request, response, chain)

        verify(chain).doFilter(request, response)
    }

    @Test
    fun `malformed ticket id cannot bypass upload limit before controller validation`() {
        val request = messageRequest("/api/support-tickets/not-a-number/messages", 11)
        val response = MockHttpServletResponse()
        val chain = mock(FilterChain::class.java)

        filter.doFilter(request, response, chain)

        assertEquals(413, response.status)
        verifyNoInteractions(chain)
    }

    @Test
    fun `message route is recognized when application has a context path`() {
        val request = messageRequest("/shop/api/support-tickets/3/messages", 11).apply {
            contextPath = "/shop"
        }
        val response = MockHttpServletResponse()
        val chain = mock(FilterChain::class.java)

        filter.doFilter(request, response, chain)

        assertEquals(413, response.status)
        verifyNoInteractions(chain)
    }

    private fun messageRequest(path: String, contentLength: Int) =
        MockHttpServletRequest("POST", path).apply {
            contentType = MediaType.MULTIPART_FORM_DATA_VALUE
            setContent(ByteArray(contentLength))
        }
}
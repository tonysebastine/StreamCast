package in.thbz.streamcast.casting

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URLEncoder
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RokuCastHandlerTest {

    private lateinit var mockInterceptor: MockInterceptor
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var rokuCastHandler: RokuCastHandler
    private val deviceIp = "192.168.1.50"
    private lateinit var testDevice: CastingDevice

    class MockInterceptor : Interceptor {
        val requests = mutableListOf<Request>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            synchronized(requests) {
                requests.add(request)
            }
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("".toResponseBody("text/plain".toMediaTypeOrNull()))
                .build()
        }
    }

    @Before
    fun setUp() {
        mockInterceptor = MockInterceptor()
        okHttpClient = OkHttpClient.Builder()
            .addInterceptor(mockInterceptor)
            .build()
        rokuCastHandler = RokuCastHandler(okHttpClient)
        testDevice = CastingDevice(
            id = "roku123",
            name = "Living Room Roku",
            ipAddress = deviceIp,
            port = 8060,
            protocolType = ProtocolType.ROKU
        )
    }

    @Test
    fun testCastMediaSendsCorrectPostRequest() = runBlocking {
        // Given
        rokuCastHandler.connect(testDevice)
        val mediaUrl = "https://example.com/movie.mp4"
        val mediaTitle = "My Movie"

        // When
        val result = rokuCastHandler.castMedia(mediaUrl, mediaTitle)

        // Then
        assertTrue(result)
        synchronized(mockInterceptor.requests) {
            assertEquals(1, mockInterceptor.requests.size)
            val request = mockInterceptor.requests[0]
            assertEquals("POST", request.method)
            
            val expectedUrl = "http://$deviceIp:8060/launch/dev?contentId=${URLEncoder.encode(mediaUrl, "UTF-8")}&mediaType=movie"
            assertEquals(expectedUrl, request.url.toString())
        }
    }

    @Test
    fun testPauseSendsCorrectPostRequest() = runBlocking {
        // Given
        rokuCastHandler.connect(testDevice)

        // When
        rokuCastHandler.pause()
        
        // Wait briefly because sendKeyPress uses okHttpClient.newCall(request).enqueue(...) asynchronously
        Thread.sleep(150)

        // Then
        synchronized(mockInterceptor.requests) {
            assertEquals(1, mockInterceptor.requests.size)
            val request = mockInterceptor.requests[0]
            assertEquals("POST", request.method)
            assertEquals("http://$deviceIp:8060/keypress/Pause", request.url.toString())
        }
    }

    @Test
    fun testResumeSendsCorrectPostRequest() = runBlocking {
        // Given
        rokuCastHandler.connect(testDevice)

        // When
        rokuCastHandler.resume()
        
        // Wait briefly because sendKeyPress uses okHttpClient.newCall(request).enqueue(...) asynchronously
        Thread.sleep(150)

        // Then
        synchronized(mockInterceptor.requests) {
            assertEquals(1, mockInterceptor.requests.size)
            val request = mockInterceptor.requests[0]
            assertEquals("POST", request.method)
            assertEquals("http://$deviceIp:8060/keypress/Play", request.url.toString())
        }
    }

    @Test
    fun testStopSendsCorrectPostRequest() = runBlocking {
        // Given
        rokuCastHandler.connect(testDevice)

        // When
        rokuCastHandler.stop()
        
        // Wait briefly because sendKeyPress uses okHttpClient.newCall(request).enqueue(...) asynchronously
        Thread.sleep(150)

        // Then
        synchronized(mockInterceptor.requests) {
            assertEquals(1, mockInterceptor.requests.size)
            val request = mockInterceptor.requests[0]
            assertEquals("POST", request.method)
            assertEquals("http://$deviceIp:8060/keypress/Home", request.url.toString())
        }
    }
}

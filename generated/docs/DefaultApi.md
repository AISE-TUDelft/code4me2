# DefaultApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**pingApiPingHead**](DefaultApi.md#pingApiPingHead) | **HEAD** /api/ping | Ping |


<a id="pingApiPingHead"></a>
# **pingApiPingHead**
> kotlin.Any pingApiPingHead()

Ping

### Example
```kotlin
// Import classes:
//import me.code4me.api.generated.infrastructure.*
//import me.code4me.api.generated.model.*

val apiInstance = DefaultApi()
try {
    val result : kotlin.Any = apiInstance.pingApiPingHead()
    println(result)
} catch (e: ClientException) {
    println("4xx response calling DefaultApi#pingApiPingHead")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling DefaultApi#pingApiPingHead")
    e.printStackTrace()
}
```

### Parameters
This endpoint does not need any parameter.

### Return type

[**kotlin.Any**](kotlin.Any.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json


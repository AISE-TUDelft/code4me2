# SessionApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**acquireSessionApiSessionAcquireGet**](SessionApi.md#acquireSessionApiSessionAcquireGet) | **GET** /api/session/acquire/ | Acquire Session |


<a id="acquireSessionApiSessionAcquireGet"></a>
# **acquireSessionApiSessionAcquireGet**
> AcquireSessionGetResponse acquireSessionApiSessionAcquireGet(authToken)

Acquire Session

Acquire or create a session token using the provided auth token.  - If the auth token is missing or invalid, return 401. - If no session is associated yet, create one and store it in Redis.

### Example
```kotlin
// Import classes:
//import me.code4me.api.generated.infrastructure.*
//import me.code4me.api.generated.model.*

val apiInstance = SessionApi()
val authToken : kotlin.String = authToken_example // kotlin.String | 
try {
    val result : AcquireSessionGetResponse = apiInstance.acquireSessionApiSessionAcquireGet(authToken)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling SessionApi#acquireSessionApiSessionAcquireGet")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling SessionApi#acquireSessionApiSessionAcquireGet")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **authToken** | **kotlin.String**|  | [optional] [default to &quot;auth_token&quot;] |

### Return type

[**AcquireSessionGetResponse**](AcquireSessionGetResponse.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json


# AcquireSessionApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**acquireSessionApiSessionAcquireGet**](AcquireSessionApi.md#acquireSessionApiSessionAcquireGet) | **GET** /api/session/acquire | Acquire Session |


<a id="acquireSessionApiSessionAcquireGet"></a>
# **acquireSessionApiSessionAcquireGet**
> AcquireSessionGetResponse acquireSessionApiSessionAcquireGet(authToken)

Acquire Session

Acquire or create a session token linked to the provided auth token.  Args:     app (App): Injected FastAPI app instance.     auth_token (str): Auth token from the client&#39;s cookies.  Returns:     JsonResponseWithStatus: Contains the session token or appropriate error response.  Behavior:     - Returns 401 if auth token is missing or invalid.     - If no session token exists for the auth token, creates a new session.     - Sets the session token as an HttpOnly cookie with expiration.

### Example
```kotlin
// Import classes:
//import me.code4me.api.generated.infrastructure.*
//import me.code4me.api.generated.model.*

val apiInstance = AcquireSessionApi()
val authToken : kotlin.String = authToken_example // kotlin.String | 
try {
    val result : AcquireSessionGetResponse = apiInstance.acquireSessionApiSessionAcquireGet(authToken)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling AcquireSessionApi#acquireSessionApiSessionAcquireGet")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling AcquireSessionApi#acquireSessionApiSessionAcquireGet")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **authToken** | **kotlin.String**|  | [optional] [default to &quot;&quot;] |

### Return type

[**AcquireSessionGetResponse**](AcquireSessionGetResponse.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json


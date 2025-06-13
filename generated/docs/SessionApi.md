# SessionApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**acquireSessionApiSessionAcquireGet**](SessionApi.md#acquireSessionApiSessionAcquireGet) | **GET** /api/session/acquire | Acquire Session |
| [**deactivateSessionApiSessionDeactivatePut**](SessionApi.md#deactivateSessionApiSessionDeactivatePut) | **PUT** /api/session/deactivate/ | Deactivate Session |


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
| **authToken** | **kotlin.String**|  | [optional] [default to &quot;&quot;] |

### Return type

[**AcquireSessionGetResponse**](AcquireSessionGetResponse.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json

<a id="deactivateSessionApiSessionDeactivatePut"></a>
# **deactivateSessionApiSessionDeactivatePut**
> DeactivateSessionPostResponse deactivateSessionApiSessionDeactivatePut(authToken)

Deactivate Session

Deactivates an active session by validating the auth token, checking the session token, and removing the session information from Redis.  Parameters: - app (App): Dependency-injected application context providing access to services. - auth_token (str): Auth token retrieved from the cookie, used to identify the user/session.  Returns: - JsonResponseWithStatus: Appropriate response depending on validation and operation outcome.

### Example
```kotlin
// Import classes:
//import me.code4me.api.generated.infrastructure.*
//import me.code4me.api.generated.model.*

val apiInstance = SessionApi()
val authToken : kotlin.String = authToken_example // kotlin.String | 
try {
    val result : DeactivateSessionPostResponse = apiInstance.deactivateSessionApiSessionDeactivatePut(authToken)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling SessionApi#deactivateSessionApiSessionDeactivatePut")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling SessionApi#deactivateSessionApiSessionDeactivatePut")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **authToken** | **kotlin.String**|  | [optional] [default to &quot;&quot;] |

### Return type

[**DeactivateSessionPostResponse**](DeactivateSessionPostResponse.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json


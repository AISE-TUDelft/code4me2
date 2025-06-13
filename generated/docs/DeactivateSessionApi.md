# DeactivateSessionApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**deactivateSessionApiSessionDeactivatePut**](DeactivateSessionApi.md#deactivateSessionApiSessionDeactivatePut) | **PUT** /api/session/deactivate/ | Deactivate Session |


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

val apiInstance = DeactivateSessionApi()
val authToken : kotlin.String = authToken_example // kotlin.String | 
try {
    val result : DeactivateSessionPostResponse = apiInstance.deactivateSessionApiSessionDeactivatePut(authToken)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling DeactivateSessionApi#deactivateSessionApiSessionDeactivatePut")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling DeactivateSessionApi#deactivateSessionApiSessionDeactivatePut")
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


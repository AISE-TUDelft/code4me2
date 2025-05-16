# AuthenticateApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**authenticateUserApiUserAuthenticatePost**](AuthenticateApi.md#authenticateUserApiUserAuthenticatePost) | **POST** /api/user/authenticate/ | Authenticate User |


<a id="authenticateUserApiUserAuthenticatePost"></a>
# **authenticateUserApiUserAuthenticatePost**
> AuthenticateUserPostResponse authenticateUserApiUserAuthenticatePost(userToAuthenticate)

Authenticate User

Authenticate a user Note: There are some nuances to how this should be handled. 1. There is a possibility that the token field is JWT token for OAuth providers (this should be checked for) 1.1. Authentication should first check if the token is a JWT token 1.2. If it is a JWT token, then the validity of the token should be checked 1.3. If the token is valid, then the user should be authenticated using the token and allocated a session 1.4. The provider is always Google 2. The filed can also simply represent a password for a user. 3. The authentication should either return a JsonResponseWithStatus with content of UserAuthenticationPostResponse or a ErrorResponse

### Example
```kotlin
// Import classes:
//import me.code4me.api.generated.infrastructure.*
//import me.code4me.api.generated.model.*

val apiInstance = AuthenticateApi()
val userToAuthenticate : UserToAuthenticate =  // UserToAuthenticate | 
try {
    val result : AuthenticateUserPostResponse = apiInstance.authenticateUserApiUserAuthenticatePost(userToAuthenticate)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling AuthenticateApi#authenticateUserApiUserAuthenticatePost")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling AuthenticateApi#authenticateUserApiUserAuthenticatePost")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **userToAuthenticate** | [**UserToAuthenticate**](UserToAuthenticate.md)|  | |

### Return type

[**AuthenticateUserPostResponse**](AuthenticateUserPostResponse.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json


# AuthenticationApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**authenticateUserApiUserAuthenticatePost**](AuthenticationApi.md#authenticateUserApiUserAuthenticatePost) | **POST** /api/user/authenticate | Authenticate User |


<a id="authenticateUserApiUserAuthenticatePost"></a>
# **authenticateUserApiUserAuthenticatePost**
> AuthenticateUserPostResponse authenticateUserApiUserAuthenticatePost(userToAuthenticate)

Authenticate User

Authenticate a user using either OAuth (via JWT) or email/password.  This endpoint supports: - OAuth: Validates a JWT token and fetches the user by email. - Email/Password: Verifies credentials against the database.  Args:     user_to_authenticate (Union[AuthenticateUserEmailPassword, AuthenticateUserOAuth]):         Either an email/password object or a JWT-based OAuth object.     app (App): FastAPI dependency that provides access to DB and config.  Returns:     JsonResponseWithStatus: Authenticated user info + auth token cookie,     or an error response.

### Example
```kotlin
// Import classes:
//import me.code4me.api.generated.infrastructure.*
//import me.code4me.api.generated.model.*

val apiInstance = AuthenticationApi()
val userToAuthenticate : UserToAuthenticate =  // UserToAuthenticate | 
try {
    val result : AuthenticateUserPostResponse = apiInstance.authenticateUserApiUserAuthenticatePost(userToAuthenticate)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling AuthenticationApi#authenticateUserApiUserAuthenticatePost")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling AuthenticationApi#authenticateUserApiUserAuthenticatePost")
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


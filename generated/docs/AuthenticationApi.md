# AuthenticationApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**authenticateUserApiUserAuthenticatePost**](AuthenticationApi.md#authenticateUserApiUserAuthenticatePost) | **POST** /api/user/authenticate | Authenticate User |


<a id="authenticateUserApiUserAuthenticatePost"></a>
# **authenticateUserApiUserAuthenticatePost**
> AuthenticateUserPostResponse authenticateUserApiUserAuthenticatePost(userToAuthenticate)

Authenticate User

Authenticate a user via either OAuth (JWT token) or traditional email/password.  This endpoint supports two methods of authentication: 1. OAuth Authentication:    - The input contains a JWT token from an OAuth provider (Google).    - The token&#39;s validity is verified.    - If valid, the user is fetched by email from the database.    - A session auth token is created and returned as a cookie. 2. Email/Password Authentication:    - The input contains user email and password.    - Credentials are verified against the database.    - If valid, a session auth token is created and returned as a cookie.  Args:     user_to_authenticate: Union of OAuth token or email/password credentials.     app: FastAPI dependency to access the application context.  Returns:     JsonResponseWithStatus: A JSON response containing the authenticated user info     and a session auth token cookie on success, or an error response otherwise.

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


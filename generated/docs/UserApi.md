# UserApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**authenticateUserApiUserAuthenticatePost**](UserApi.md#authenticateUserApiUserAuthenticatePost) | **POST** /api/user/authenticate/ | Authenticate User |
| [**checkVerificationApiUserVerifyCheckGet**](UserApi.md#checkVerificationApiUserVerifyCheckGet) | **GET** /api/user/verify/check | Check Verification |
| [**createUserApiUserCreatePost**](UserApi.md#createUserApiUserCreatePost) | **POST** /api/user/create | Create User |
| [**deleteUserApiUserDeleteDelete**](UserApi.md#deleteUserApiUserDeleteDelete) | **DELETE** /api/user/delete | Delete User |
| [**updateUserApiUserUpdatePut**](UserApi.md#updateUserApiUserUpdatePut) | **PUT** /api/user/update | Update User |
| [**verifyEmailApiUserVerifyGet**](UserApi.md#verifyEmailApiUserVerifyGet) | **GET** /api/user/verify/ | Verify Email |


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

val apiInstance = UserApi()
val userToAuthenticate : UserToAuthenticate =  // UserToAuthenticate | 
try {
    val result : AuthenticateUserPostResponse = apiInstance.authenticateUserApiUserAuthenticatePost(userToAuthenticate)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling UserApi#authenticateUserApiUserAuthenticatePost")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling UserApi#authenticateUserApiUserAuthenticatePost")
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

<a id="checkVerificationApiUserVerifyCheckGet"></a>
# **checkVerificationApiUserVerifyCheckGet**
> kotlin.Any checkVerificationApiUserVerifyCheckGet(authToken)

Check Verification

Check if the user is verified

### Example
```kotlin
// Import classes:
//import me.code4me.api.generated.infrastructure.*
//import me.code4me.api.generated.model.*

val apiInstance = UserApi()
val authToken : kotlin.String = authToken_example // kotlin.String | 
try {
    val result : kotlin.Any = apiInstance.checkVerificationApiUserVerifyCheckGet(authToken)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling UserApi#checkVerificationApiUserVerifyCheckGet")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling UserApi#checkVerificationApiUserVerifyCheckGet")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **authToken** | **kotlin.String**|  | [optional] [default to &quot;auth_token&quot;] |

### Return type

[**kotlin.Any**](kotlin.Any.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json

<a id="createUserApiUserCreatePost"></a>
# **createUserApiUserCreatePost**
> CreateUserPostResponse createUserApiUserCreatePost(userToCreate)

Create User

Create a new user in the system.  Args:     user_to_create (Union[Queries.CreateUser, Queries.CreateUserOauth]):         The user data to create, can be standard or OAuth-based.     app (App):         The application instance, injected by FastAPI&#39;s dependency system.  Returns:     JsonResponseWithStatus: Response with status code and content.  Steps:     1. Check if the user already exists by email.     2. If OAuth, verify the JWT token and email.     3. Create the user in the database if not exists.     4. Send verification email.     5. Return appropriate response.

### Example
```kotlin
// Import classes:
//import me.code4me.api.generated.infrastructure.*
//import me.code4me.api.generated.model.*

val apiInstance = UserApi()
val userToCreate : UserToCreate =  // UserToCreate | 
try {
    val result : CreateUserPostResponse = apiInstance.createUserApiUserCreatePost(userToCreate)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling UserApi#createUserApiUserCreatePost")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling UserApi#createUserApiUserCreatePost")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **userToCreate** | [**UserToCreate**](UserToCreate.md)|  | |

### Return type

[**CreateUserPostResponse**](CreateUserPostResponse.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json

<a id="deleteUserApiUserDeleteDelete"></a>
# **deleteUserApiUserDeleteDelete**
> DeleteUserDeleteResponse deleteUserApiUserDeleteDelete(deleteData, authToken)

Delete User

Delete the authenticated user&#39;s account and optionally their data.  Args:     delete_data (bool): Flag indicating whether to delete associated data (default: False).     auth_token (str): Authentication token stored in browser cookies.     app (App): Application instance with access to database and session managers.  Returns:     JsonResponseWithStatus: A success message or an appropriate error response.

### Example
```kotlin
// Import classes:
//import me.code4me.api.generated.infrastructure.*
//import me.code4me.api.generated.model.*

val apiInstance = UserApi()
val deleteData : kotlin.Boolean = true // kotlin.Boolean | Delete user's data
val authToken : kotlin.String = authToken_example // kotlin.String | 
try {
    val result : DeleteUserDeleteResponse = apiInstance.deleteUserApiUserDeleteDelete(deleteData, authToken)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling UserApi#deleteUserApiUserDeleteDelete")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling UserApi#deleteUserApiUserDeleteDelete")
    e.printStackTrace()
}
```

### Parameters
| **deleteData** | **kotlin.Boolean**| Delete user&#39;s data | [optional] [default to false] |
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **authToken** | **kotlin.String**|  | [optional] [default to &quot;auth_token&quot;] |

### Return type

[**DeleteUserDeleteResponse**](DeleteUserDeleteResponse.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json

<a id="updateUserApiUserUpdatePut"></a>
# **updateUserApiUserUpdatePut**
> UpdateUserPutResponse updateUserApiUserUpdatePut(updateUser, authToken)

Update User

Update the currently authenticated user&#39;s data.  Args: - user_to_update: Pydantic model containing fields to update. - app: Application context, injected by FastAPI. - auth_token: Authentication token stored in browser cookies.  Returns: - JSON response with updated user information if successful. - Appropriate error response if auth token is missing or invalid.

### Example
```kotlin
// Import classes:
//import me.code4me.api.generated.infrastructure.*
//import me.code4me.api.generated.model.*

val apiInstance = UserApi()
val updateUser : UpdateUser =  // UpdateUser | 
val authToken : kotlin.String = authToken_example // kotlin.String | 
try {
    val result : UpdateUserPutResponse = apiInstance.updateUserApiUserUpdatePut(updateUser, authToken)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling UserApi#updateUserApiUserUpdatePut")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling UserApi#updateUserApiUserUpdatePut")
    e.printStackTrace()
}
```

### Parameters
| **updateUser** | [**UpdateUser**](UpdateUser.md)|  | |
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **authToken** | **kotlin.String**|  | [optional] [default to &quot;auth_token&quot;] |

### Return type

[**UpdateUserPutResponse**](UpdateUserPutResponse.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json

<a id="verifyEmailApiUserVerifyGet"></a>
# **verifyEmailApiUserVerifyGet**
> kotlin.Any verifyEmailApiUserVerifyGet(token)

Verify Email

Verify user email with the provided token

### Example
```kotlin
// Import classes:
//import me.code4me.api.generated.infrastructure.*
//import me.code4me.api.generated.model.*

val apiInstance = UserApi()
val token : kotlin.String = token_example // kotlin.String | Verification token
try {
    val result : kotlin.Any = apiInstance.verifyEmailApiUserVerifyGet(token)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling UserApi#verifyEmailApiUserVerifyGet")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling UserApi#verifyEmailApiUserVerifyGet")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **token** | **kotlin.String**| Verification token | |

### Return type

[**kotlin.Any**](kotlin.Any.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json


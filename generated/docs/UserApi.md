# UserApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**authenticateUserApiUserAuthenticatePost**](UserApi.md#authenticateUserApiUserAuthenticatePost) | **POST** /api/user/authenticate | Authenticate User |
| [**checkVerificationApiUserVerifyCheckGet**](UserApi.md#checkVerificationApiUserVerifyCheckGet) | **GET** /api/user/verify/check | Check Verification |
| [**createUserApiUserCreatePost**](UserApi.md#createUserApiUserCreatePost) | **POST** /api/user/create | Create User |
| [**deleteUserApiUserDeleteDelete**](UserApi.md#deleteUserApiUserDeleteDelete) | **DELETE** /api/user/delete | Delete User |
| [**resendVerificationEmailApiUserVerifyResendPost**](UserApi.md#resendVerificationEmailApiUserVerifyResendPost) | **POST** /api/user/verify/resend | Resend Verification Email |
| [**updateUserApiUserUpdatePut**](UserApi.md#updateUserApiUserUpdatePut) | **PUT** /api/user/update | Update User |
| [**verifyEmailApiUserVerifyPost**](UserApi.md#verifyEmailApiUserVerifyPost) | **POST** /api/user/verify/ | Verify Email |


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
> GetVerificationGetResponse checkVerificationApiUserVerifyCheckGet(authToken)

Check Verification

Check if the currently authenticated user has verified their email.  Parameters: - app (App): Dependency-injected application context providing access to services. - auth_token (str): Authentication token retrieved from the user&#39;s cookie.  Returns: - JsonResponseWithStatus: User verification status or appropriate error message.

### Example
```kotlin
// Import classes:
//import me.code4me.api.generated.infrastructure.*
//import me.code4me.api.generated.model.*

val apiInstance = UserApi()
val authToken : kotlin.String = authToken_example // kotlin.String | 
try {
    val result : GetVerificationGetResponse = apiInstance.checkVerificationApiUserVerifyCheckGet(authToken)
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
| **authToken** | **kotlin.String**|  | [optional] [default to &quot;&quot;] |

### Return type

[**GetVerificationGetResponse**](GetVerificationGetResponse.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json

<a id="createUserApiUserCreatePost"></a>
# **createUserApiUserCreatePost**
> CreateUserPostResponse createUserApiUserCreatePost(userToCreate)

Create User

Create a new user in the system using standard or OAuth-based data.  Args:     user_to_create (Union[CreateUser, CreateUserOauth]):         User data from the request body (standard or OAuth-based).     app (App):         Application context with access to database and services.  Returns:     JsonResponseWithStatus: JSON response indicating success or failure.  Flow:     1. Check if a user already exists with the given email.     2. If using OAuth, validate the JWT token.     3. Insert the new user into the database.     4. Send a verification email via Celery.     5. Return HTTP 201 with the new user ID.

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

Delete the authenticated user&#39;s account and optionally their associated data.  Args:     delete_data (bool): If True, removes all user-related data (default is False).     auth_token (str): Auth token provided in cookies to authenticate the user.     app (App): Dependency-injected app instance with DB and Redis access.  Returns:     JsonResponseWithStatus: A success or error response depending on the outcome.

### Example
```kotlin
// Import classes:
//import me.code4me.api.generated.infrastructure.*
//import me.code4me.api.generated.model.*

val apiInstance = UserApi()
val deleteData : kotlin.Boolean = true // kotlin.Boolean | Delete user's associated data
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
| **deleteData** | **kotlin.Boolean**| Delete user&#39;s associated data | [optional] [default to false] |
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **authToken** | **kotlin.String**|  | [optional] [default to &quot;&quot;] |

### Return type

[**DeleteUserDeleteResponse**](DeleteUserDeleteResponse.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json

<a id="resendVerificationEmailApiUserVerifyResendPost"></a>
# **resendVerificationEmailApiUserVerifyResendPost**
> ResendVerificationEmailPostResponse resendVerificationEmailApiUserVerifyResendPost(authToken)

Resend Verification Email

Resend a verification email to the user if not already verified.  Parameters: - app (App): Application context for accessing services. - auth_token (str): Auth token from cookie identifying the user.  Returns: - JsonResponseWithStatus: Success confirmation or error message.

### Example
```kotlin
// Import classes:
//import me.code4me.api.generated.infrastructure.*
//import me.code4me.api.generated.model.*

val apiInstance = UserApi()
val authToken : kotlin.String = authToken_example // kotlin.String | 
try {
    val result : ResendVerificationEmailPostResponse = apiInstance.resendVerificationEmailApiUserVerifyResendPost(authToken)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling UserApi#resendVerificationEmailApiUserVerifyResendPost")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling UserApi#resendVerificationEmailApiUserVerifyResendPost")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **authToken** | **kotlin.String**|  | [optional] [default to &quot;&quot;] |

### Return type

[**ResendVerificationEmailPostResponse**](ResendVerificationEmailPostResponse.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json

<a id="updateUserApiUserUpdatePut"></a>
# **updateUserApiUserUpdatePut**
> UpdateUserPutResponse updateUserApiUserUpdatePut(updateUser, authToken)

Update User

Update the currently authenticated user&#39;s data.  Args:     user_to_update (Queries.UpdateUser): Fields the user wants to update.     app (App): Injected application instance with DB and Redis access.     auth_token (str): Auth token stored in the user&#39;s browser cookies.  Returns:     JsonResponseWithStatus: Contains updated user data or error info.

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
| **authToken** | **kotlin.String**|  | [optional] [default to &quot;&quot;] |

### Return type

[**UpdateUserPutResponse**](UpdateUserPutResponse.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json

<a id="verifyEmailApiUserVerifyPost"></a>
# **verifyEmailApiUserVerifyPost**
> VerifyUserPostHTMLResponse verifyEmailApiUserVerifyPost(token)

Verify Email

Verify the user&#39;s email address using the provided verification token.  Parameters: - token (str): Token for email verification, passed via query parameter. - app (App): Application context for accessing services.  Returns: - HTMLResponseWithStatus: HTML response indicating verification outcome.

### Example
```kotlin
// Import classes:
//import me.code4me.api.generated.infrastructure.*
//import me.code4me.api.generated.model.*

val apiInstance = UserApi()
val token : kotlin.String = token_example // kotlin.String | Verification token
try {
    val result : VerifyUserPostHTMLResponse = apiInstance.verifyEmailApiUserVerifyPost(token)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling UserApi#verifyEmailApiUserVerifyPost")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling UserApi#verifyEmailApiUserVerifyPost")
    e.printStackTrace()
}
```

### Parameters
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **token** | **kotlin.String**| Verification token | |

### Return type

[**VerifyUserPostHTMLResponse**](VerifyUserPostHTMLResponse.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json


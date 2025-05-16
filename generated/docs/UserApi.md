# UserApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**authenticateUserApiUserAuthenticatePost**](UserApi.md#authenticateUserApiUserAuthenticatePost) | **POST** /api/user/authenticate/ | Authenticate User |
| [**createUserApiUserCreatePost**](UserApi.md#createUserApiUserCreatePost) | **POST** /api/user/create/ | Create User |
| [**deleteUserApiUserDeleteDelete**](UserApi.md#deleteUserApiUserDeleteDelete) | **DELETE** /api/user/delete/ | Delete User |
| [**updateUserApiUserUpdatePut**](UserApi.md#updateUserApiUserUpdatePut) | **PUT** /api/user/update/ | Update User |


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

<a id="createUserApiUserCreatePost"></a>
# **createUserApiUserCreatePost**
> CreateUserPostResponse createUserApiUserCreatePost(userToCreate)

Create User

Create a new user 1. The user should be created in the database if it does not exist 2. The user should be sent a verification email 3. The user should be sent a success message 4. If the user already exists, then a 409 error should be returned

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
> DeleteUserDeleteResponse deleteUserApiUserDeleteDelete(deleteUserData, sessionToken)

Delete User

### Example
```kotlin
// Import classes:
//import me.code4me.api.generated.infrastructure.*
//import me.code4me.api.generated.model.*

val apiInstance = UserApi()
val deleteUserData : kotlin.Boolean = true // kotlin.Boolean | Delete users data
val sessionToken : kotlin.String = sessionToken_example // kotlin.String | 
try {
    val result : DeleteUserDeleteResponse = apiInstance.deleteUserApiUserDeleteDelete(deleteUserData, sessionToken)
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
| **deleteUserData** | **kotlin.Boolean**| Delete users data | [optional] [default to false] |
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **sessionToken** | **kotlin.String**|  | [optional] [default to &quot;session_token&quot;] |

### Return type

[**DeleteUserDeleteResponse**](DeleteUserDeleteResponse.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json

<a id="updateUserApiUserUpdatePut"></a>
# **updateUserApiUserUpdatePut**
> UpdateUserPutResponse updateUserApiUserUpdatePut(updateUser, sessionToken)

Update User

### Example
```kotlin
// Import classes:
//import me.code4me.api.generated.infrastructure.*
//import me.code4me.api.generated.model.*

val apiInstance = UserApi()
val updateUser : UpdateUser =  // UpdateUser | 
val sessionToken : kotlin.String = sessionToken_example // kotlin.String | 
try {
    val result : UpdateUserPutResponse = apiInstance.updateUserApiUserUpdatePut(updateUser, sessionToken)
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
| **sessionToken** | **kotlin.String**|  | [optional] [default to &quot;session_token&quot;] |

### Return type

[**UpdateUserPutResponse**](UpdateUserPutResponse.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json


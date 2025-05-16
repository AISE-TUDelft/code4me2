# CreateApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**createUserApiUserCreatePost**](CreateApi.md#createUserApiUserCreatePost) | **POST** /api/user/create/ | Create User |


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

val apiInstance = CreateApi()
val userToCreate : UserToCreate =  // UserToCreate | 
try {
    val result : CreateUserPostResponse = apiInstance.createUserApiUserCreatePost(userToCreate)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling CreateApi#createUserApiUserCreatePost")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling CreateApi#createUserApiUserCreatePost")
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


# UpdateApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**updateUserApiUserUpdatePut**](UpdateApi.md#updateUserApiUserUpdatePut) | **PUT** /api/user/update/ | Update User |


<a id="updateUserApiUserUpdatePut"></a>
# **updateUserApiUserUpdatePut**
> UpdateUserPutResponse updateUserApiUserUpdatePut(updateUser, sessionToken)

Update User

### Example
```kotlin
// Import classes:
//import me.code4me.api.generated.infrastructure.*
//import me.code4me.api.generated.model.*

val apiInstance = UpdateApi()
val updateUser : UpdateUser =  // UpdateUser | 
val sessionToken : kotlin.String = sessionToken_example // kotlin.String | 
try {
    val result : UpdateUserPutResponse = apiInstance.updateUserApiUserUpdatePut(updateUser, sessionToken)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling UpdateApi#updateUserApiUserUpdatePut")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling UpdateApi#updateUserApiUserUpdatePut")
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


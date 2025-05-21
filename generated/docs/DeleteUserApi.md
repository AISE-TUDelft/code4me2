# DeleteUserApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**deleteUserApiUserDeleteDelete**](DeleteUserApi.md#deleteUserApiUserDeleteDelete) | **DELETE** /api/user/delete/ | Delete User |


<a id="deleteUserApiUserDeleteDelete"></a>
# **deleteUserApiUserDeleteDelete**
> DeleteUserDeleteResponse deleteUserApiUserDeleteDelete(deleteUserData, sessionToken)

Delete User

### Example
```kotlin
// Import classes:
//import me.code4me.api.generated.infrastructure.*
//import me.code4me.api.generated.model.*

val apiInstance = DeleteUserApi()
val deleteUserData : kotlin.Boolean = true // kotlin.Boolean | Delete users data
val sessionToken : kotlin.String = sessionToken_example // kotlin.String | 
try {
    val result : DeleteUserDeleteResponse = apiInstance.deleteUserApiUserDeleteDelete(deleteUserData, sessionToken)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling DeleteUserApi#deleteUserApiUserDeleteDelete")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling DeleteUserApi#deleteUserApiUserDeleteDelete")
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


# UserVerificationApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**checkVerificationApiUserVerifyCheckGet**](UserVerificationApi.md#checkVerificationApiUserVerifyCheckGet) | **GET** /api/user/verify/check | Check Verification |
| [**resendVerificationEmailApiUserVerifyResendGet**](UserVerificationApi.md#resendVerificationEmailApiUserVerifyResendGet) | **GET** /api/user/verify/resend | Resend Verification Email |
| [**verifyEmailApiUserVerifyGet**](UserVerificationApi.md#verifyEmailApiUserVerifyGet) | **GET** /api/user/verify/ | Verify Email |


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

val apiInstance = UserVerificationApi()
val authToken : kotlin.String = authToken_example // kotlin.String | 
try {
    val result : kotlin.Any = apiInstance.checkVerificationApiUserVerifyCheckGet(authToken)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling UserVerificationApi#checkVerificationApiUserVerifyCheckGet")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling UserVerificationApi#checkVerificationApiUserVerifyCheckGet")
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

<a id="resendVerificationEmailApiUserVerifyResendGet"></a>
# **resendVerificationEmailApiUserVerifyResendGet**
> kotlin.Any resendVerificationEmailApiUserVerifyResendGet(authToken)

Resend Verification Email

Resend verification email to the user

### Example
```kotlin
// Import classes:
//import me.code4me.api.generated.infrastructure.*
//import me.code4me.api.generated.model.*

val apiInstance = UserVerificationApi()
val authToken : kotlin.String = authToken_example // kotlin.String | 
try {
    val result : kotlin.Any = apiInstance.resendVerificationEmailApiUserVerifyResendGet(authToken)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling UserVerificationApi#resendVerificationEmailApiUserVerifyResendGet")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling UserVerificationApi#resendVerificationEmailApiUserVerifyResendGet")
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

val apiInstance = UserVerificationApi()
val token : kotlin.String = token_example // kotlin.String | Verification token
try {
    val result : kotlin.Any = apiInstance.verifyEmailApiUserVerifyGet(token)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling UserVerificationApi#verifyEmailApiUserVerifyGet")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling UserVerificationApi#verifyEmailApiUserVerifyGet")
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


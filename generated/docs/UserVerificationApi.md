# UserVerificationApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**checkVerificationApiUserVerifyCheckGet**](UserVerificationApi.md#checkVerificationApiUserVerifyCheckGet) | **GET** /api/user/verify/check | Check Verification |
| [**resendVerificationEmailApiUserVerifyResendPost**](UserVerificationApi.md#resendVerificationEmailApiUserVerifyResendPost) | **POST** /api/user/verify/resend | Resend Verification Email |
| [**verifyEmailApiUserVerifyPost**](UserVerificationApi.md#verifyEmailApiUserVerifyPost) | **POST** /api/user/verify/ | Verify Email |


<a id="checkVerificationApiUserVerifyCheckGet"></a>
# **checkVerificationApiUserVerifyCheckGet**
> GetVerificationGetResponse checkVerificationApiUserVerifyCheckGet(authToken)

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
    val result : GetVerificationGetResponse = apiInstance.checkVerificationApiUserVerifyCheckGet(authToken)
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
| **authToken** | **kotlin.String**|  | [optional] [default to &quot;&quot;] |

### Return type

[**GetVerificationGetResponse**](GetVerificationGetResponse.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json

<a id="resendVerificationEmailApiUserVerifyResendPost"></a>
# **resendVerificationEmailApiUserVerifyResendPost**
> ResendVerificationEmailPostResponse resendVerificationEmailApiUserVerifyResendPost(authToken)

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
    val result : ResendVerificationEmailPostResponse = apiInstance.resendVerificationEmailApiUserVerifyResendPost(authToken)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling UserVerificationApi#resendVerificationEmailApiUserVerifyResendPost")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling UserVerificationApi#resendVerificationEmailApiUserVerifyResendPost")
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

<a id="verifyEmailApiUserVerifyPost"></a>
# **verifyEmailApiUserVerifyPost**
> VerifyUserPostHTMLResponse verifyEmailApiUserVerifyPost(token)

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
    val result : VerifyUserPostHTMLResponse = apiInstance.verifyEmailApiUserVerifyPost(token)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling UserVerificationApi#verifyEmailApiUserVerifyPost")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling UserVerificationApi#verifyEmailApiUserVerifyPost")
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


# RequestCompletionApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**requestCompletionApiCompletionRequestPost**](RequestCompletionApi.md#requestCompletionApiCompletionRequestPost) | **POST** /api/completion/request/ | Request Completion |


<a id="requestCompletionApiCompletionRequestPost"></a>
# **requestCompletionApiCompletionRequestPost**
> CompletionPostResponse requestCompletionApiCompletionRequestPost(requestCompletion, sessionToken)

Request Completion

Request code completions based on provided context.

### Example
```kotlin
// Import classes:
//import me.code4me.api.generated.infrastructure.*
//import me.code4me.api.generated.model.*

val apiInstance = RequestCompletionApi()
val requestCompletion : RequestCompletion =  // RequestCompletion | 
val sessionToken : kotlin.String = sessionToken_example // kotlin.String | 
try {
    val result : CompletionPostResponse = apiInstance.requestCompletionApiCompletionRequestPost(requestCompletion, sessionToken)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling RequestCompletionApi#requestCompletionApiCompletionRequestPost")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling RequestCompletionApi#requestCompletionApiCompletionRequestPost")
    e.printStackTrace()
}
```

### Parameters
| **requestCompletion** | [**RequestCompletion**](RequestCompletion.md)|  | |
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **sessionToken** | **kotlin.String**|  | [optional] [default to &quot;session_token&quot;] |

### Return type

[**CompletionPostResponse**](CompletionPostResponse.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json


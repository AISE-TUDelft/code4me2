# CompletionFeedbackApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**submitCompletionFeedbackApiCompletionFeedbackPost**](CompletionFeedbackApi.md#submitCompletionFeedbackApiCompletionFeedbackPost) | **POST** /api/completion/feedback/ | Submit Completion Feedback |


<a id="submitCompletionFeedbackApiCompletionFeedbackPost"></a>
# **submitCompletionFeedbackApiCompletionFeedbackPost**
> CompletionFeedbackPostResponse submitCompletionFeedbackApiCompletionFeedbackPost(feedbackCompletion, sessionToken)

Submit Completion Feedback

Submit feedback on a generated completion.

### Example
```kotlin
// Import classes:
//import me.code4me.api.generated.infrastructure.*
//import me.code4me.api.generated.model.*

val apiInstance = CompletionFeedbackApi()
val feedbackCompletion : FeedbackCompletion =  // FeedbackCompletion | 
val sessionToken : kotlin.String = sessionToken_example // kotlin.String | 
try {
    val result : CompletionFeedbackPostResponse = apiInstance.submitCompletionFeedbackApiCompletionFeedbackPost(feedbackCompletion, sessionToken)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling CompletionFeedbackApi#submitCompletionFeedbackApiCompletionFeedbackPost")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling CompletionFeedbackApi#submitCompletionFeedbackApiCompletionFeedbackPost")
    e.printStackTrace()
}
```

### Parameters
| **feedbackCompletion** | [**FeedbackCompletion**](FeedbackCompletion.md)|  | |
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **sessionToken** | **kotlin.String**|  | [optional] [default to &quot;session_token&quot;] |

### Return type

[**CompletionFeedbackPostResponse**](CompletionFeedbackPostResponse.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json


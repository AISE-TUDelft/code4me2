# CompletionApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**getCompletionsByQueryApiCompletionQueryIdGet**](CompletionApi.md#getCompletionsByQueryApiCompletionQueryIdGet) | **GET** /api/completion/{query_id} | Get Completions By Query |
| [**requestCompletionApiCompletionRequestPost**](CompletionApi.md#requestCompletionApiCompletionRequestPost) | **POST** /api/completion/request/ | Request Completion |
| [**submitCompletionFeedbackApiCompletionFeedbackPost**](CompletionApi.md#submitCompletionFeedbackApiCompletionFeedbackPost) | **POST** /api/completion/feedback/ | Submit Completion Feedback |


<a id="getCompletionsByQueryApiCompletionQueryIdGet"></a>
# **getCompletionsByQueryApiCompletionQueryIdGet**
> CompletionPostResponseInput getCompletionsByQueryApiCompletionQueryIdGet(queryId, sessionToken)

Get Completions By Query

Get completions for a specific query ID.

### Example
```kotlin
// Import classes:
//import me.code4me.api.generated.infrastructure.*
//import me.code4me.api.generated.model.*

val apiInstance = CompletionApi()
val queryId : java.util.UUID = 38400000-8cf0-11bd-b23e-10b96e4ef00d // java.util.UUID | 
val sessionToken : kotlin.String = sessionToken_example // kotlin.String | 
try {
    val result : CompletionPostResponseInput = apiInstance.getCompletionsByQueryApiCompletionQueryIdGet(queryId, sessionToken)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling CompletionApi#getCompletionsByQueryApiCompletionQueryIdGet")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling CompletionApi#getCompletionsByQueryApiCompletionQueryIdGet")
    e.printStackTrace()
}
```

### Parameters
| **queryId** | **java.util.UUID**|  | |
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **sessionToken** | **kotlin.String**|  | [optional] [default to &quot;session_token&quot;] |

### Return type

[**CompletionPostResponseInput**](CompletionPostResponseInput.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json

<a id="requestCompletionApiCompletionRequestPost"></a>
# **requestCompletionApiCompletionRequestPost**
> CompletionPostResponseInput requestCompletionApiCompletionRequestPost(requestCompletion, sessionToken, projectToken)

Request Completion

Request code completions based on provided context.

### Example
```kotlin
// Import classes:
//import me.code4me.api.generated.infrastructure.*
//import me.code4me.api.generated.model.*

val apiInstance = CompletionApi()
val requestCompletion : RequestCompletion =  // RequestCompletion | 
val sessionToken : kotlin.String = sessionToken_example // kotlin.String | 
val projectToken : kotlin.String = projectToken_example // kotlin.String | 
try {
    val result : CompletionPostResponseInput = apiInstance.requestCompletionApiCompletionRequestPost(requestCompletion, sessionToken, projectToken)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling CompletionApi#requestCompletionApiCompletionRequestPost")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling CompletionApi#requestCompletionApiCompletionRequestPost")
    e.printStackTrace()
}
```

### Parameters
| **requestCompletion** | [**RequestCompletion**](RequestCompletion.md)|  | |
| **sessionToken** | **kotlin.String**|  | [optional] [default to &quot;session_token&quot;] |
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **projectToken** | **kotlin.String**|  | [optional] [default to &quot;project_token&quot;] |

### Return type

[**CompletionPostResponseInput**](CompletionPostResponseInput.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json

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

val apiInstance = CompletionApi()
val feedbackCompletion : FeedbackCompletion =  // FeedbackCompletion | 
val sessionToken : kotlin.String = sessionToken_example // kotlin.String | 
try {
    val result : CompletionFeedbackPostResponse = apiInstance.submitCompletionFeedbackApiCompletionFeedbackPost(feedbackCompletion, sessionToken)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling CompletionApi#submitCompletionFeedbackApiCompletionFeedbackPost")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling CompletionApi#submitCompletionFeedbackApiCompletionFeedbackPost")
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


# CompletionApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**getCompletionsByQueryApiCompletionQueryIdGet**](CompletionApi.md#getCompletionsByQueryApiCompletionQueryIdGet) | **GET** /api/completion/{query_id} | Get Completions By Query |
| [**requestCompletionApiCompletionRequestPost**](CompletionApi.md#requestCompletionApiCompletionRequestPost) | **POST** /api/completion/request/ | Request Completion |
| [**submitCompletionFeedbackApiCompletionFeedbackPost**](CompletionApi.md#submitCompletionFeedbackApiCompletionFeedbackPost) | **POST** /api/completion/feedback/ | Submit Completion Feedback |
| [**updateMultiFileContextApiCompletionMultiFileContextUpdatePost**](CompletionApi.md#updateMultiFileContextApiCompletionMultiFileContextUpdatePost) | **POST** /api/completion/multi-file-context/update/ | Update Multi File Context |


<a id="getCompletionsByQueryApiCompletionQueryIdGet"></a>
# **getCompletionsByQueryApiCompletionQueryIdGet**
> CompletionPostResponse getCompletionsByQueryApiCompletionQueryIdGet(queryId, sessionToken)

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
    val result : CompletionPostResponse = apiInstance.getCompletionsByQueryApiCompletionQueryIdGet(queryId, sessionToken)
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

[**CompletionPostResponse**](CompletionPostResponse.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json

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

val apiInstance = CompletionApi()
val requestCompletion : RequestCompletion =  // RequestCompletion | 
val sessionToken : kotlin.String = sessionToken_example // kotlin.String | 
try {
    val result : CompletionPostResponse = apiInstance.requestCompletionApiCompletionRequestPost(requestCompletion, sessionToken)
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

<a id="updateMultiFileContextApiCompletionMultiFileContextUpdatePost"></a>
# **updateMultiFileContextApiCompletionMultiFileContextUpdatePost**
> MultiFileContextUpdatePostResponse updateMultiFileContextApiCompletionMultiFileContextUpdatePost(updateMultiFileContext, sessionToken)

Update Multi File Context

Update the context for a specific query ID.

### Example
```kotlin
// Import classes:
//import me.code4me.api.generated.infrastructure.*
//import me.code4me.api.generated.model.*

val apiInstance = CompletionApi()
val updateMultiFileContext : UpdateMultiFileContext =  // UpdateMultiFileContext | 
val sessionToken : kotlin.String = sessionToken_example // kotlin.String | 
try {
    val result : MultiFileContextUpdatePostResponse = apiInstance.updateMultiFileContextApiCompletionMultiFileContextUpdatePost(updateMultiFileContext, sessionToken)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling CompletionApi#updateMultiFileContextApiCompletionMultiFileContextUpdatePost")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling CompletionApi#updateMultiFileContextApiCompletionMultiFileContextUpdatePost")
    e.printStackTrace()
}
```

### Parameters
| **updateMultiFileContext** | [**UpdateMultiFileContext**](UpdateMultiFileContext.md)|  | |
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **sessionToken** | **kotlin.String**|  | [optional] [default to &quot;session_token&quot;] |

### Return type

[**MultiFileContextUpdatePostResponse**](MultiFileContextUpdatePostResponse.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json


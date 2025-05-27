# GetCompletionsApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**getCompletionsByQueryApiCompletionQueryIdGet**](GetCompletionsApi.md#getCompletionsByQueryApiCompletionQueryIdGet) | **GET** /api/completion/{query_id} | Get Completions By Query |


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

val apiInstance = GetCompletionsApi()
val queryId : java.util.UUID = 38400000-8cf0-11bd-b23e-10b96e4ef00d // java.util.UUID | 
val sessionToken : kotlin.String = sessionToken_example // kotlin.String | 
try {
    val result : CompletionPostResponse = apiInstance.getCompletionsByQueryApiCompletionQueryIdGet(queryId, sessionToken)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling GetCompletionsApi#getCompletionsByQueryApiCompletionQueryIdGet")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling GetCompletionsApi#getCompletionsByQueryApiCompletionQueryIdGet")
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


# MultiFileContextApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**updateMultiFileContextApiCompletionMultiFileContextUpdatePost**](MultiFileContextApi.md#updateMultiFileContextApiCompletionMultiFileContextUpdatePost) | **POST** /api/completion/multi-file-context/update | Update Multi File Context |


<a id="updateMultiFileContextApiCompletionMultiFileContextUpdatePost"></a>
# **updateMultiFileContextApiCompletionMultiFileContextUpdatePost**
> MultiFileContextUpdatePostResponse updateMultiFileContextApiCompletionMultiFileContextUpdatePost(updateMultiFileContext, sessionToken, projectToken)

Update Multi File Context

Update the context for a specific query ID.

### Example
```kotlin
// Import classes:
//import me.code4me.api.generated.infrastructure.*
//import me.code4me.api.generated.model.*

val apiInstance = MultiFileContextApi()
val updateMultiFileContext : UpdateMultiFileContext =  // UpdateMultiFileContext | 
val sessionToken : kotlin.String = sessionToken_example // kotlin.String | 
val projectToken : kotlin.String = projectToken_example // kotlin.String | 
try {
    val result : MultiFileContextUpdatePostResponse = apiInstance.updateMultiFileContextApiCompletionMultiFileContextUpdatePost(updateMultiFileContext, sessionToken, projectToken)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling MultiFileContextApi#updateMultiFileContextApiCompletionMultiFileContextUpdatePost")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling MultiFileContextApi#updateMultiFileContextApiCompletionMultiFileContextUpdatePost")
    e.printStackTrace()
}
```

### Parameters
| **updateMultiFileContext** | [**UpdateMultiFileContext**](UpdateMultiFileContext.md)|  | |
| **sessionToken** | **kotlin.String**|  | [optional] [default to &quot;session_token&quot;] |
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **projectToken** | **kotlin.String**|  | [optional] [default to &quot;project_token&quot;] |

### Return type

[**MultiFileContextUpdatePostResponse**](MultiFileContextUpdatePostResponse.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json


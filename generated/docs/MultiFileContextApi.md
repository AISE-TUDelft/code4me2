# MultiFileContextApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**updateMultiFileContextApiCompletionMultiFileContextUpdatePost**](MultiFileContextApi.md#updateMultiFileContextApiCompletionMultiFileContextUpdatePost) | **POST** /api/completion/multi-file-context/update | Update Multi File Context |


<a id="updateMultiFileContextApiCompletionMultiFileContextUpdatePost"></a>
# **updateMultiFileContextApiCompletionMultiFileContextUpdatePost**
> MultiFileContextUpdatePostResponse updateMultiFileContextApiCompletionMultiFileContextUpdatePost(updateMultiFileContext, sessionToken, projectToken)

Update Multi File Context

Endpoint to update multi-file context content for a given project and session.  Steps: - Validate session token and retrieve session info from Redis. - Validate project token and retrieve project info from Redis. - Apply line-level context updates to the existing multi-file contexts. - Redact secrets in the updated content lines. - Remove any files with empty content. - Update the context change logs accordingly. - Store the updated project info back in Redis. - Return the updated context in the response.  Args:     context_update: UpdateMultiFileContext model containing the changes to apply.     app: The application instance (dependency-injected).     session_token: Session token from cookies.     project_token: Project token from cookies.  Returns:     JsonResponseWithStatus containing the updated multi-file context or an error response.

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
| **sessionToken** | **kotlin.String**|  | [optional] [default to &quot;&quot;] |
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **projectToken** | **kotlin.String**|  | [optional] [default to &quot;&quot;] |

### Return type

[**MultiFileContextUpdatePostResponse**](MultiFileContextUpdatePostResponse.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json


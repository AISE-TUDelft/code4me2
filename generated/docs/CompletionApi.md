# CompletionApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**getCompletionsByQueryApiCompletionQueryIdGet**](CompletionApi.md#getCompletionsByQueryApiCompletionQueryIdGet) | **GET** /api/completion/{query_id} | Get Completions By Query |
| [**requestCompletionApiCompletionRequestPost**](CompletionApi.md#requestCompletionApiCompletionRequestPost) | **POST** /api/completion/request | Request Completion |
| [**submitCompletionFeedbackApiCompletionFeedbackPost**](CompletionApi.md#submitCompletionFeedbackApiCompletionFeedbackPost) | **POST** /api/completion/feedback | Submit Completion Feedback |


<a id="getCompletionsByQueryApiCompletionQueryIdGet"></a>
# **getCompletionsByQueryApiCompletionQueryIdGet**
> CompletionPostResponse getCompletionsByQueryApiCompletionQueryIdGet(queryId, sessionToken)

Get Completions By Query

Retrieve code completions associated with a specific query ID.  This endpoint validates the user&#39;s session token and authorization, ensures the requested query exists and belongs to the requesting user, and then fetches all the associated completion generations from the database.  Parameters: - query_id (UUID): The unique identifier for the meta query whose completions are requested. - app (App, dependency): The application instance providing database and Redis access. - session_token (str, cookie): The session token cookie for user authentication.  Returns: - JsonResponseWithStatus: JSON response with HTTP status and either the completions data   or an error response detailing the failure reason.  Possible responses: - 200: Successfully retrieved completions for the given query ID. - 401: Session token is invalid, expired, or missing. - 403: User does not have access rights to the requested query. - 404: The requested query ID does not exist. - 422, 429: Various client errors (validation or rate limiting). - 500: Internal server error while retrieving completions.

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
| **sessionToken** | **kotlin.String**|  | [optional] [default to &quot;&quot;] |

### Return type

[**CompletionPostResponse**](CompletionPostResponse.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json

<a id="requestCompletionApiCompletionRequestPost"></a>
# **requestCompletionApiCompletionRequestPost**
> CompletionPostResponse requestCompletionApiCompletionRequestPost(requestCompletion, sessionToken, projectToken)

Request Completion

Handle a code completion request.  Steps: - Authenticate session, user, and project tokens via Redis. - Redact any secrets from the context before processing. - Prepare optional Celery tasks for storing context and telemetry. - Aggregate multi-file context if available. - Run completion models concurrently using a thread pool. - Queue database update tasks asynchronously using Celery. - Return completion results or appropriate error responses.

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
    val result : CompletionPostResponse = apiInstance.requestCompletionApiCompletionRequestPost(requestCompletion, sessionToken, projectToken)
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
| **sessionToken** | **kotlin.String**|  | [optional] [default to &quot;&quot;] |
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **projectToken** | **kotlin.String**|  | [optional] [default to &quot;&quot;] |

### Return type

[**CompletionPostResponse**](CompletionPostResponse.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json

<a id="submitCompletionFeedbackApiCompletionFeedbackPost"></a>
# **submitCompletionFeedbackApiCompletionFeedbackPost**
> CompletionFeedbackPostResponse submitCompletionFeedbackApiCompletionFeedbackPost(feedbackCompletion, sessionToken, projectToken)

Submit Completion Feedback

Submit feedback on a generated completion.  This endpoint validates the user&#39;s session and project tokens against Redis, verifies the user&#39;s authorization to provide feedback on the given query, and enqueues asynchronous tasks to update generation status and optionally save ground truth feedback.  Parameters: - feedback: The feedback data submitted by the user, including acceptance   status and optional ground truth. - app: Dependency-injected application instance providing DB and Redis access. - session_token: Session cookie used to authenticate the user session. - project_token: Project cookie used to authorize project-level access.  Returns: - JsonResponseWithStatus: A response indicating success or describing errors,   including authorization failures, missing records, or internal errors.

### Example
```kotlin
// Import classes:
//import me.code4me.api.generated.infrastructure.*
//import me.code4me.api.generated.model.*

val apiInstance = CompletionApi()
val feedbackCompletion : FeedbackCompletion =  // FeedbackCompletion | 
val sessionToken : kotlin.String = sessionToken_example // kotlin.String | 
val projectToken : kotlin.String = projectToken_example // kotlin.String | 
try {
    val result : CompletionFeedbackPostResponse = apiInstance.submitCompletionFeedbackApiCompletionFeedbackPost(feedbackCompletion, sessionToken, projectToken)
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
| **sessionToken** | **kotlin.String**|  | [optional] [default to &quot;&quot;] |
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **projectToken** | **kotlin.String**|  | [optional] [default to &quot;&quot;] |

### Return type

[**CompletionFeedbackPostResponse**](CompletionFeedbackPostResponse.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json


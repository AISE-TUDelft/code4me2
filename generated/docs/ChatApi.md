# ChatApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**deleteChatApiChatDeleteChatIdDelete**](ChatApi.md#deleteChatApiChatDeleteChatIdDelete) | **DELETE** /api/chat/delete/{chat_id} | Delete Chat |
| [**getChatHistoryApiChatGetPageNumberGet**](ChatApi.md#getChatHistoryApiChatGetPageNumberGet) | **GET** /api/chat/get/{page_number} | Get Chat History |
| [**requestChatCompletionApiChatRequestPost**](ChatApi.md#requestChatCompletionApiChatRequestPost) | **POST** /api/chat/request | Request Chat Completion |


<a id="deleteChatApiChatDeleteChatIdDelete"></a>
# **deleteChatApiChatDeleteChatIdDelete**
> DeleteChatSuccessResponse deleteChatApiChatDeleteChatIdDelete(chatId, sessionToken, projectToken)

Delete Chat

Delete a specific chat by its ID. Validates that the user has access to the chat through their session and project tokens.

### Example
```kotlin
// Import classes:
//import me.code4me.api.generated.infrastructure.*
//import me.code4me.api.generated.model.*

val apiInstance = ChatApi()
val chatId : java.util.UUID = 38400000-8cf0-11bd-b23e-10b96e4ef00d // java.util.UUID | 
val sessionToken : kotlin.String = sessionToken_example // kotlin.String | 
val projectToken : kotlin.String = projectToken_example // kotlin.String | 
try {
    val result : DeleteChatSuccessResponse = apiInstance.deleteChatApiChatDeleteChatIdDelete(chatId, sessionToken, projectToken)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling ChatApi#deleteChatApiChatDeleteChatIdDelete")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling ChatApi#deleteChatApiChatDeleteChatIdDelete")
    e.printStackTrace()
}
```

### Parameters
| **chatId** | **java.util.UUID**|  | |
| **sessionToken** | **kotlin.String**|  | [optional] [default to &quot;&quot;] |
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **projectToken** | **kotlin.String**|  | [optional] [default to &quot;&quot;] |

### Return type

[**DeleteChatSuccessResponse**](DeleteChatSuccessResponse.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json

<a id="getChatHistoryApiChatGetPageNumberGet"></a>
# **getChatHistoryApiChatGetPageNumberGet**
> ChatHistoryResponsePage getChatHistoryApiChatGetPageNumberGet(pageNumber, sessionToken, projectToken)

Get Chat History

Retrieve a page of chat history for a specific project associated with the current session.  Parameters: - app (App): Dependency-injected FastAPI application instance. - page_number (int): The page number of the chat history to retrieve. - session_token (str): Session token stored in a cookie. - project_token (str): Project token stored in a cookie.  Returns: - JsonResponseWithStatus: Paginated chat history or an error response.

### Example
```kotlin
// Import classes:
//import me.code4me.api.generated.infrastructure.*
//import me.code4me.api.generated.model.*

val apiInstance = ChatApi()
val pageNumber : kotlin.Int = 56 // kotlin.Int | 
val sessionToken : kotlin.String = sessionToken_example // kotlin.String | 
val projectToken : kotlin.String = projectToken_example // kotlin.String | 
try {
    val result : ChatHistoryResponsePage = apiInstance.getChatHistoryApiChatGetPageNumberGet(pageNumber, sessionToken, projectToken)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling ChatApi#getChatHistoryApiChatGetPageNumberGet")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling ChatApi#getChatHistoryApiChatGetPageNumberGet")
    e.printStackTrace()
}
```

### Parameters
| **pageNumber** | **kotlin.Int**|  | |
| **sessionToken** | **kotlin.String**|  | [optional] [default to &quot;&quot;] |
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **projectToken** | **kotlin.String**|  | [optional] [default to &quot;&quot;] |

### Return type

[**ChatHistoryResponsePage**](ChatHistoryResponsePage.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: Not defined
 - **Accept**: application/json

<a id="requestChatCompletionApiChatRequestPost"></a>
# **requestChatCompletionApiChatRequestPost**
> ChatHistoryResponse requestChatCompletionApiChatRequestPost(requestChatCompletion, sessionToken, projectToken)

Request Chat Completion

Request chat completions based on provided messages.  The contract here is that the request always contains all the history of the chat as well  We do this because it could be that the user modifies the chat history in the frontend and we want to ensure that the chat completions are based on the latest state of the chat.  Take the case where the user edits a message in the chat history midway through a chat. We don&#39;t want to be generating a completion based on the old state of the chat.

### Example
```kotlin
// Import classes:
//import me.code4me.api.generated.infrastructure.*
//import me.code4me.api.generated.model.*

val apiInstance = ChatApi()
val requestChatCompletion : RequestChatCompletion =  // RequestChatCompletion | 
val sessionToken : kotlin.String = sessionToken_example // kotlin.String | 
val projectToken : kotlin.String = projectToken_example // kotlin.String | 
try {
    val result : ChatHistoryResponse = apiInstance.requestChatCompletionApiChatRequestPost(requestChatCompletion, sessionToken, projectToken)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling ChatApi#requestChatCompletionApiChatRequestPost")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling ChatApi#requestChatCompletionApiChatRequestPost")
    e.printStackTrace()
}
```

### Parameters
| **requestChatCompletion** | [**RequestChatCompletion**](RequestChatCompletion.md)|  | |
| **sessionToken** | **kotlin.String**|  | [optional] [default to &quot;&quot;] |
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **projectToken** | **kotlin.String**|  | [optional] [default to &quot;&quot;] |

### Return type

[**ChatHistoryResponse**](ChatHistoryResponse.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json


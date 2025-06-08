# ProjectApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**activateProjectApiProjectActivatePut**](ProjectApi.md#activateProjectApiProjectActivatePut) | **PUT** /api/project/activate/ | Activate Project |
| [**createProjectApiProjectCreatePost**](ProjectApi.md#createProjectApiProjectCreatePost) | **POST** /api/project/create/ | Create Project |


<a id="activateProjectApiProjectActivatePut"></a>
# **activateProjectApiProjectActivatePut**
> ActivateProjectPostResponse activateProjectApiProjectActivatePut(activateProject, authToken)

Activate Project

Activates the project by following these steps: 1. Validate the provided auth token 2. If valid, return confirmation 3. If invalid, return an appropriate error response 4. The project might exist in redis or in the database, if it is in the database, it should be fetched from there and put in redis if it is in the redis, its expiration time should be updated.

### Example
```kotlin
// Import classes:
//import me.code4me.api.generated.infrastructure.*
//import me.code4me.api.generated.model.*

val apiInstance = ProjectApi()
val activateProject : ActivateProject =  // ActivateProject | 
val authToken : kotlin.String = authToken_example // kotlin.String | 
try {
    val result : ActivateProjectPostResponse = apiInstance.activateProjectApiProjectActivatePut(activateProject, authToken)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling ProjectApi#activateProjectApiProjectActivatePut")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling ProjectApi#activateProjectApiProjectActivatePut")
    e.printStackTrace()
}
```

### Parameters
| **activateProject** | [**ActivateProject**](ActivateProject.md)|  | |
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **authToken** | **kotlin.String**|  | [optional] [default to &quot;auth_token&quot;] |

### Return type

[**ActivateProjectPostResponse**](ActivateProjectPostResponse.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json

<a id="createProjectApiProjectCreatePost"></a>
# **createProjectApiProjectCreatePost**
> CreateProjectPostResponse createProjectApiProjectCreatePost(createProject, authToken)

Create Project

Create a new project 1. Validate the provided session token 2. If valid, create a project and return the project token 3. If invalid, return an appropriate error response

### Example
```kotlin
// Import classes:
//import me.code4me.api.generated.infrastructure.*
//import me.code4me.api.generated.model.*

val apiInstance = ProjectApi()
val createProject : CreateProject =  // CreateProject | 
val authToken : kotlin.String = authToken_example // kotlin.String | 
try {
    val result : CreateProjectPostResponse = apiInstance.createProjectApiProjectCreatePost(createProject, authToken)
    println(result)
} catch (e: ClientException) {
    println("4xx response calling ProjectApi#createProjectApiProjectCreatePost")
    e.printStackTrace()
} catch (e: ServerException) {
    println("5xx response calling ProjectApi#createProjectApiProjectCreatePost")
    e.printStackTrace()
}
```

### Parameters
| **createProject** | [**CreateProject**](CreateProject.md)|  | |
| Name | Type | Description  | Notes |
| ------------- | ------------- | ------------- | ------------- |
| **authToken** | **kotlin.String**|  | [optional] [default to &quot;auth_token&quot;] |

### Return type

[**CreateProjectPostResponse**](CreateProjectPostResponse.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json


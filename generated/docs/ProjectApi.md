# ProjectApi

All URIs are relative to *http://localhost*

| Method | HTTP request | Description |
| ------------- | ------------- | ------------- |
| [**activateProjectApiProjectActivatePut**](ProjectApi.md#activateProjectApiProjectActivatePut) | **PUT** /api/project/activate | Activate Project |
| [**createProjectApiProjectCreatePost**](ProjectApi.md#createProjectApiProjectCreatePost) | **POST** /api/project/create | Create Project |


<a id="activateProjectApiProjectActivatePut"></a>
# **activateProjectApiProjectActivatePut**
> ActivateProjectPostResponse activateProjectApiProjectActivatePut(activateProject, authToken)

Activate Project

Activate a project for a user session by performing the following:  1. Validate the auth token and session token from cookies. 2. If the project exists in Redis, update its expiration time. 3. If the project is not found in Redis, fetch it from the database and cache it. 4. Associate the project with the user session and user in the database if not already linked. 5. Return a success response with the project token set as an HttpOnly cookie.  Args:     activate_project_request: Request data containing the project ID to activate.     app: FastAPI dependency injection to get app context.     auth_token: Auth token from cookie to validate user identity.  Returns:     JsonResponseWithStatus: Success or error response with appropriate status and messages.

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
| **authToken** | **kotlin.String**|  | [optional] [default to &quot;&quot;] |

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

Create a new project for an authenticated user.  Steps: 1. Validate the provided auth token to get session info. 2. Verify the session token is valid. 3. Create a new project in the database. 4. Associate the project with the session and the user. 5. Store project metadata in Redis. 6. Return the project token as an HttpOnly cookie in the response.  Args:     project_to_create: Project data provided in the request body.     app: FastAPI dependency to access the app context.     auth_token: Auth token passed as a cookie for authentication.  Returns:     JsonResponseWithStatus: JSON response with project token or error details.

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
| **authToken** | **kotlin.String**|  | [optional] [default to &quot;&quot;] |

### Return type

[**CreateProjectPostResponse**](CreateProjectPostResponse.md)

### Authorization

No authorization required

### HTTP request headers

 - **Content-Type**: application/json
 - **Accept**: application/json


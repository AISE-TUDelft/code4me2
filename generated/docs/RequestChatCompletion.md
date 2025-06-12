
# RequestChatCompletion

## Properties
| Name | Type | Description | Notes |
| ------------ | ------------- | ------------- | ------------- |
| **modelIds** | **kotlin.collections.List&lt;kotlin.Int&gt;** | Models to use for chat completion |  |
| **chatId** | [**java.util.UUID**](java.util.UUID.md) | Chat ID |  |
| **messages** | **kotlin.collections.List&lt;kotlin.collections.List&lt;kotlin.Any&gt;&gt;** | Chat messages as a list of tuples |  |
| **context** | [**ContextData**](ContextData.md) | Context data for completion |  |
| **contextualTelemetry** | [**ContextualTelemetryData**](ContextualTelemetryData.md) | Contextual telemetry data |  |
| **behavioralTelemetry** | [**BehavioralTelemetryData**](BehavioralTelemetryData.md) | Behavioral telemetry data |  |
| **storeContext** | **kotlin.Boolean** |  |  [optional] |
| **storeContextualTelemetry** | **kotlin.Boolean** |  |  [optional] |
| **storeBehavioralTelemetry** | **kotlin.Boolean** |  |  [optional] |
| **webEnabled** | **kotlin.Boolean** |  |  [optional] |




<!-- Plugin description -->
This is the official plugin for the 2nd version of Code4Me. :)
<!-- Plugin description end -->

# For Developers
In order to generate the api client, you need to have the following installed:
- The [OpenAPI Generator](https://openapi-generator.tech/docs/installation/)

then you can run the following command to generate the client:
```bash
npx @openapitools/openapi-generator-cli generate \
  -i src/main/resources/backend/api/openapi.json \
  -g kotlin \
  -o generated/ \
  --api-package me.code4me.api.generated.api \
  --model-package me.code4me.api.generated.model \
  --invoker-package me.code4me.api.generated.invoker \
  --package-name me.code4me.api.generated
```

In order to ensure that there are no problems, make sure that the wrapper in the `generated` folder is commented out from the `build.gradle` file.
Also, make sure that the java version being used is 17.


# Module System Overview
The project supports a modular architecture where features are encapsulated as modules. Each module can be enabled/disabled individually, may have dependencies on other modules, and can also contain submodules. This system is dynamically configured and initialized based on a HOCON configuration file.

## Module Initialization
Modules are initialized by the ModuleManager, which performs the following steps at runtime:

1) **Load Configuration**: The config.modules.available list is parsed to identify which modules and submodules are available, including their metadata (ID, class, type, description, enabled status, etc.).

2) **Store Modules**: Modules are registered using storeModules(), and then recursively through registerModuleRecursively(), which also handles submodules.

3) **Enable Defaults**: Modules marked with enabled = true in the config are automatically enabled if not already active.

4) **Resolve Dependencies**: Dependencies defined in dependencies are respected. Hard dependencies (isHard = true) will force-enable required submodules.

5) **Initialize Modules**: Enabled modules are initialized via initializeModules(). A module is only initialized if its dependencies are resolved and it hasn't been initialized before.

## Adding a New Module
To add a new module to the system:

1) **Implement the Module Class**:
Your class should implement the PluginModule interface and define behavior for initializeModules(), collectData(), and any preferences via getPreferenceList().

2) **Register in HOCON Config**:
Add your module and optional submodules to the configuration file under config.modules.available, e.g.:

```
{
    id = "MyNewModule"
    class = "me.code4me.services.modules.custom.MyNewModule"
    name = "My New Module"
    type = "custom"
    description = "Provides new functionality"
    enabled = true
    submodules = [ ... ]         # Array must be present, can be empty 
    dependencies = [ ... ]       # One dependency must be added per submodule 
}
```

Note: a dependency shows a parent-child relationship between two modules, in other words, module B is in the list of dependencies of A if and only if B is a submodule (child) of A.
3) **Define Module Category (Optional)**:
If this module type doesn't already exist in config.modules.categories, define it:

```
custom = {
    path = "me.code4me.services.modules.custom"
    description = "Custom modules"
}
```
4) **Ready to go**:
During application startup, the module is picked up and stored by the ModuleManager. If it’s enabled in the config, it will be initialized automatically.

 ## Module Dependencies
Dependencies are defined at the module level using the dependencies block. Each dependency can be:

**Hard (isHard = true)**: The submodule will be automatically enabled if not already.

**Soft (isHard = false)**: Initialization proceeds even if the submodule is disabled.

Example:

```
dependencies = [ 
    {
        moduleId = "EditorContextRetrievalModule"
        isHard = false
    }
]
```

## Preferences and UI Integration
Module preferences are displayed and managed via the plugin's settings UI. The UI uses metadata from each module’s getPreferenceList() implementation to render user-editable fields (Boolean, String, Int, etc.).

Only enabled modules (and submodules) appear active in the UI. Modules with unresolved hard dependencies cannot be disabled.

# Authentication 

For more information on how authentication is implemented, please refer to the documentation of the backend: https://gitlab.ewi.tudelft.nl/cse2000-software-project/2024-2025/cluster-a/17c/server/-/blob/main/README.md?ref_type=heads#requestresponse-flow

# Code Completion 

Documentation coming soon...
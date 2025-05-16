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
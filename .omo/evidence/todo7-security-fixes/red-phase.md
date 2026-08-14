# RED evidence

Base: `43a4dea08eea733367b10c96332073da9911c608`

## Functions

Command:

```text
cd functions && npm run build && node --test lib/index.test.js lib/plant-identification-storage-contract.test.js
```

Exit: `1`

Observed contract failures before production edits:

- compiled `identifyPlant` endpoint metadata assertion: `false !== true`
- missing App Check reached the handler and returned `Sign-in is required` instead of Firebase callable `Unauthenticated`
- invalid App Check was logged as allowed and reached the handler
- four stored candidates produced `Missing expected rejection`

The provider sort/truncate-before-storage test and the 1..3 stored-candidate replay test already passed.

## Android

Command:

```text
./gradlew :feature:identify:testDebugUnitTest \
  --tests 'com.planterior.helper.feature.identify.FirebaseIdentificationGatewayTest' \
  --tests 'com.planterior.helper.feature.identify.IdentificationControllerTest' \
  --console=plain
```

Exit: `1`

Observed failures before production edits:

- `FirebaseIdentificationGatewayTest > malformed and four-candidate callable payloads fail closed`
- `IdentificationControllerTest > response enforces one to three candidate bounds`

Summary: 7 tests, 2 failed.

# Release authentication entry points must remain inspectable and callable after R8.
-keep,allowoptimization class com.planterior.helper.auth.AuthRuntime { *; }
-keep,allowoptimization class com.planterior.helper.feature.auth.GoogleCredentialProvider { *; }
-keep,allowoptimization class com.planterior.helper.feature.auth.AppleWebAuthProvider { *; }
-keep,allowoptimization class com.planterior.helper.feature.auth.FirebaseIdentityAdapter { *; }
-keep,allowoptimization class com.planterior.helper.feature.auth.FirebaseAppleCallable { *; }

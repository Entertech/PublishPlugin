# Local Publish Execution

Use this mode when Gradle runs on the current machine. The destination may be
Maven Local, GitHub Packages, Central, or all enabled remote providers.

## Inspect without changing configuration

1. Confirm the repository-root Gradle wrapper exists.
2. Resolve the module and component from applied plugins:
   - `com.android.library` means Library;
   - `java-gradle-plugin` or the plugin's supported Gradle Plugin rule means
     Plugin;
   - ambiguous component types must fail.
3. Read `PublishInfo` coordinates and the selected provider configuration.
4. Use `:module:tasks --all` only when task availability needs verification.
5. Resolve runtime credentials from Gradle properties, environment variables,
   or ignored `.publish/local.properties` without printing values.

Maven Local must not require or validate remote credentials. A dedicated remote
task must validate only its provider. `all` requires at least one enabled
provider.

## Command construction

Construct exactly one module task:

```bash
./gradlew :module:<exact-task-name> --no-daemon --stacktrace
```

When the user explicitly supplies a version, add:

```text
-PpublishVersion=<version>
```

For prebuilt artifacts, add the arguments described in
`prebuilt-release.md`. Never pass credentials or signing material on the
command line. Prefer environment variables or the ignored local credential
file.

Do not add `-PpublishTarget`; the public task fixes the destination.

## Result handling

Capture the Gradle exit status and final output. On success, use the output to
report exact publication coordinates. For Maven Local, report the resolved
`~/.m2/repository` path when known.

On failure, summarize the concrete Gradle or repository error. Do not retry a
remote task automatically. For `all`, preserve evidence of providers completed
before the failure and recommend only the failed provider's dedicated task as
a possible user-authorized retry.

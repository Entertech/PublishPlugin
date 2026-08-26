# Prebuilt Artifact Publish

Use this reference in addition to the selected execution-mode reference when
the release consumes an existing artifact bundle.

## Required inputs

- `artifactSource=prebuilt`;
- project-relative `artifactBundlePath`;
- `publish-artifacts.json` inside that directory;
- optional Actions artifact name already configured by the caller workflow.

Reject absolute paths, `..` traversal, paths outside the repository/workspace,
and symlinks escaping the bundle root.

## Validate before release

Validate the complete manifest before any upload or workflow dispatch:

- supported schema version;
- nonempty publication coordinates and expected version;
- allowed packaging and file roles;
- every declared file exists and remains inside the bundle;
- declared size and SHA-256 match;
- main/POM and destination-specific companion files are complete;
- Central requirements include sources, javadoc, and required signatures;
- Gradle Plugin bundles include implementation and marker publications when
  applicable.

Do not infer coordinates or roles from filenames, modify the input directory,
generate missing files, sign artifacts, or upload files absent from the
manifest. A validation failure ends the release attempt and should be handed
back to configuration/artifact preparation.

## Local Gradle arguments

Append only:

```text
-PartifactSource=prebuilt
-PartifactBundlePath=<validated-project-relative-path>
```

The resulting task graph must not include compile, assemble, bundle, jar, or
other project packaging tasks.

## GitHub Actions

Verify the caller workflow already declares `artifact_source: prebuilt` and the
correct `artifact_bundle_path`. If `artifact_bundle_artifact` is configured,
the artifact must be available to that workflow; different jobs do not share a
filesystem. Do not upload or create that Actions artifact as an implicit part
of release execution.

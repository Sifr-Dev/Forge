# Security boundaries

Forge executes manifests as the invoking user. Child processes inherit the explicitly allowlisted environment but retain ordinary filesystem and network privileges. There is no sandbox, namespace isolation, syscall policy or privilege separation. Only run trusted manifests.

Inputs cannot use absolute paths, `..` escapes or symbolic links. Commands are argv arrays, not interpolated shell strings; an explicit `/bin/sh -c` in a manifest intentionally gives that manifest shell semantics. Output capture is bounded, but an action can write elsewhere under the user's permissions.

The local cache checks content hashes before restoring declared outputs. This detects accidental or tested corruption, not a malicious actor able to modify both content and metadata. Cache entries are published only after successful execution and output verification. Failure and timeout results are not cached.

No remote cache or distributed execution is implemented. No cryptographic provenance claim is made merely from hashing inputs.

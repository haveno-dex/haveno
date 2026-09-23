# User Guide

This document is a guide for Haveno users.

## Diagnosing Out-of-Memory Shutdowns

If Haveno repeatedly shuts down due to a Java out-of-memory error, you can enable a heap dump to help investigate the cause. Add this line to `haveno.properties` in your [application data directory](installing.md#run-haveno), then restart Haveno:

```properties
dumpHeapOnOutOfMemoryError=true
```

Alternatively, launch Haveno with `--dumpHeapOnOutOfMemoryError=true`. Command-line settings take precedence over `haveno.properties`.

This option is disabled by default. When enabled, the JVM attempts to write `haveno-<pid>-<timestamp>.hprof` in the application data directory before exiting on a Java heap out-of-memory error. The destination is also logged at startup. It does not create a dump on normal exit or when the operating system kills the process, and does not capture the memory of separate Monero processes or native wallet allocations.

Heap dumps can contain wallet keys, passwords, trade details and other private data. Do not upload them to public issues or share them without understanding the sensitive information they contain. They can require disk space comparable to the Java heap and delay shutdown while being written. Keep enough free disk space and delete dumps after diagnosis; Haveno does not remove them automatically.

Account backups include heap dumps left in the application data directory, including their unencrypted sensitive contents. Delete the dumps or move them to a secure location outside that directory before creating a backup.

Remove the setting or set it to `false`, then restart Haveno to stop enabling dumps. This option leaves any manually configured JVM heap-dump flags unchanged when disabled.

## Running a Local Monero Node

For the best experience using Haveno, it is highly recommended to run your own local Monero node to improve security and responsiveness.

By default, Haveno will automatically connect to a local node if it is detected. Additionally, Haveno will automatically start and connect to your local Monero node if it was last used and is currently offline.

Otherwise, Haveno will connect to a pre-configured remote node, unless manually configured otherwise.

## UI Scaling For High DPI Displays

If the UI is too small on your display, you can force UI scaling to a value of your choice using one of the following approaches. The examples below scale the UI to 200%, you can replace the '2' with a value of your choice, e.g. '1.5' for 150%.

### Edit The Application Shortcut (KDE Plasma)

1) Open the properties of your shortcut to haveno
2) Click on Program
3) Add `JAVA_TOOL_OPTIONS=-Dglass.gtk.uiScale=2` to the environment variables

### Launching From The Command Line

Prepend `JAVA_TOOL_OPTIONS=-Dglass.gtk.uiScale=2` to the command you use to launch haveno (e.g. `JAVA_TOOL_OPTIONS=-Dglass.gtk.uiScale=2 haveno-desktop`).

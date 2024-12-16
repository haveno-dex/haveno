# User Guide

This document is a guide for Haveno users.

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

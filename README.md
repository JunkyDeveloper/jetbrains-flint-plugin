# Flint Testing Suite

![Build](https://github.com/JunkyDeveloper/jetbrains-flint-plugin/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)

Flint Testing Suite integrates the Flint testing workflow into RustRover and other JetBrains IDEs with Rust support. It adds run configurations for `flint-steel` benchmarks and the `flint-viz` visualizer, manages the supporting Flint toolchain, and exposes project-level settings for common Flint environment variables.

Use it when working on SteelMC or Flint-based Rust projects and you want to run selected tests, benchmark the full suite, or launch the visualizer without leaving the IDE.

## Features

- Flint run configuration for selected or full `flint-steel` benchmark runs.
- Flint Viz run configuration for serving the local `flint-viz` HTTP UI.
- Project settings under `Settings/Preferences > Tools > Flint`.
- Managed `flint-steel` checkout under the IDE system directory.
- Optional local `flint-core` override for development against a checked-out crate.
- Tag refresh and tag selection backed by the bundled `flint-index` binary.
- Environment mapping for `INDEX_NAME`, `DEFAULT_TAG`, `TEST_PATH`, `FLINT_TEST`, `FLINT_TAGS`, `FLINT_PATTERN`, and `FLINT_VIZ_URL`.

## Requirements

- A JetBrains IDE with Rust plugin support, such as RustRover.
- A Rust/Cargo toolchain available to the IDE process.
- Access to the configured `flint-steel` repository.

The plugin bundles Linux x86-64 helper binaries for `flint-index` and `flint-viz`. On other platforms, install compatible tools and make them available on `PATH` where supported.

## Usage

1. Open a SteelMC or Flint-compatible Rust workspace.
2. Open `Settings/Preferences > Tools > Flint`.
3. Configure the `flint-steel` repository URL, test path, optional local `flint-core` path, and default Flint environment values.
4. Use `Refresh tags` to rebuild the Flint test index and populate the tag selector.
5. Create a `Flint` run configuration to run selected tests or all benchmarks.
6. Create a `Flint Viz` run configuration to start the local visualizer.

## Installation

- Using the IDE built-in plugin system:

  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "Flint Testing Suite"</kbd> >
  <kbd>Install</kbd>

- Using JetBrains Marketplace:

  Go to [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID) and install it by clicking the <kbd>Install to ...</kbd> button in case your IDE is running.

  You can also download the [latest release](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID/versions) from JetBrains Marketplace and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Install plugin from disk...</kbd>

- Manually:

  Download the [latest release](https://github.com/JunkyDeveloper/jetbrains-flint-plugin/releases/latest) and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Install plugin from disk...</kbd>

## Development

This plugin is based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template

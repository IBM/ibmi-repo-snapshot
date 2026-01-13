# ibmi-repo-snapshot
IBM i RPM repo snapshot tool

A Java-based tool for taking snapshots of RPM-based repositories, generating .repo files, and serving or installing them for IBM i. This allows package installers such (primarily yum) to be configured to install open source software from the served RPM-repositories. Supports both CLI and GUI (admin/user) modes.

## Features
 - Download and snapshot RPM repositories using a YAML config file.
 - Generate and install .repo files for IBM i or other YUM-based systems.
 - Serve repositories via an embedded HTTP server (Jetty-based).
 - Admin and User GUI modes for interactive use.
 - CLI mode for automation and scripting.
 - Automatic repodata generation (requires createrepo or createrepo_c).
 - Handles multiple repo versions and concurrent downloads.

## Requirements
 - Java 8 or later
 - Maven (for building)
 - createrepo or createrepo_c (for repodata generation)

## Building

Navigate to the `ibmi-repo-snapshot` folder and use 

```
mvn package
```
or 
```
mvn clean package
```
This will create the corresponding `RepoTool.jar` file under the `target` folder

## Usage
Snapshots downloaded by the tool are stored by default in the `snapshots` folder. The tool can be run from the parent folder using:

```
java -jar target\RepoTool.jar
```
followed by the following options
 - `-c, --config <file>` : Path to YAML config file. Example -c config.yaml
 - `--adminGui` : Launch admin GUI (full control).
 - `--userGui` : Launch user GUI (serve existing snapshots only).
 - `--serve` : Start HTTP server to serve a repo. Requires `-c` option
 - `--serve-existing <dir>` : Use already-downloaded snapshots. The argument may be a snapshot `.zip` file or an unzipped directory
 - `--install-repos` : Install generated .repo files to system directory. This is required to install the snapshot packages through yum
 - `--install-target <dir>` : Custom target for .repo files (default: /QOpenSys/etc/yum/repos.d).
 - `-p, --port <port>` : Port for HTTP server (default: 9000).
 - `--host <host>` : Host/IP for .repo file generation (default: localhost).
> [!NOTE]
> - You must specify exactly one of `-c <file>` OR `--adminGui` OR `--userGui` OR `--serve-existing` options
> - `--adminGui` and `--userGui` are standalone options and should be used on their own, not with other options


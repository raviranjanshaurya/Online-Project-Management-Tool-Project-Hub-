# Project Hub Pro — GUVI Online Project Management Tool

## One-click Windows setup

1. Install and start **MySQL Server 8+** and extract **Apache Tomcat 10.1** anywhere under Desktop, Documents, Downloads, or common installation directories.
2. Extract this complete ZIP, including `src`, `web`, `lib`, and `database.sql`.
3. Double-click **START_PROJECT.bat**. Enter only your MySQL username (press Enter for `root`) and password when asked. The password is hidden.
4. Open `http://localhost:8080/ProjectHub/` (opens automatically). On first use, create an Admin account through the web page. Admin can then create Manager and Member accounts.

The launcher discovers a compatible JDK (17+), sets `JAVA_HOME`/`JRE_HOME` for its own process (not system-wide), and downloads Temurin 21 into `tools/jdk` if needed (internet required). It discovers Tomcat, builds Java, executes the idempotent database schema via bundled MySQL JDBC driver (no `mysql.exe` or MySQL Workbench required), deploys the app and starts Tomcat. Existing database records are retained.

**Limitations:** No program can run on literally every old JDK: this Tomcat 10.1/Jakarta Servlet project requires Java 11+, and the included launcher uses JDK 17+ to compile reliably. It does not install MySQL Server or Tomcat; these must be installed/extracted and MySQL must be running locally at `127.0.0.1:3306`. Tomcat needs permission to write to its `webapps` folder, and port 8080 must be free. Java downloads require internet. Credentials need `CREATE DATABASE` and table permissions for the initial run. Keep project folders together.

## GUVI project functions

- **Admin:** user and role management, project administration, workspace settings
- **Manager:** create/manage assigned projects, assign tasks, monitor progress
- **Member:** view assigned tasks/projects and update task statuses
- UI: responsive dashboard, project metrics, kanban board, task list, progress charts, animations and dark mode
- Technologies: Java Servlet, JDBC, MySQL, HTML/CSS/JavaScript, Apache Tomcat. Data model in `database.sql`; Java source in `src/ProjectApp.java` and `src/DatabaseBootstrap.java`.

## Important security/assessment notes

The launcher does **not** write the database password to source files or system-wide environment variables; it passes it to the running Tomcat process. This is coursework/demo software, not a production-secured app. The demo uses SHA-256 account hashes rather than an adaptive password KDF, and lacks CSRF defenses. Do not expose this server to the internet. The initial Admin account is created through the web UI instead of shipping a default password.

## Troubleshooting

- Cannot connect to MySQL: start the MySQL service; check username/password, port 3306, and account permissions.
- Port 8080 occupied: stop other Tomcat instances via their `bin/shutdown.bat`.
- Tomcat not detected: extract Tomcat 10.1 under `Desktop`, `Downloads`, `Documents`, or a common installation path.
- No download connection: preinstall a 17+ JDK and ensure `javac.exe` is available, or set `JAVA_HOME`.
- If website fails to load, inspect `Tomcat/logs`.

### Run on another laptop

Copy the entire project folder or ZIP. Install MySQL Server and Tomcat 10.1 and launch `START_PROJECT.bat` to create a fresh schema; no system JAVA_HOME configuration or manual SQL commands required.

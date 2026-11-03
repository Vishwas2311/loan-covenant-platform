#!/bin/sh
# One-shot local-stack setup, run after Core has applied its migrations.
# The demo MSSQL data sources ship pointing at the dev H2 file; repoint them at this SQL Server
# through a read-only login so the demo portfolio assesses end to end. The password is not stored in
# cov_data_source: the sources name it with secretRef, and Core reads COVENANT_DS_DEMO_READER_PASSWORD.
set -e
sql() { /opt/mssql-tools18/bin/sqlcmd -C -b -S sqlserver -U sa -P "$MSSQL_SA_PASSWORD" -d covenant "$@"; }
until sql -h -1 -Q "SET NOCOUNT ON; IF OBJECT_ID('obligor_metrics_demo') IS NULL THROW 50000, 'waiting', 1" >/dev/null 2>&1; do
  echo "waiting for Core migrations"; sleep 5
done
sql -v pw="$DEMO_READER_PASSWORD" -Q "
IF SUSER_ID('demo_reader') IS NULL CREATE LOGIN demo_reader WITH PASSWORD = '\$(pw)', CHECK_POLICY = OFF;
ELSE ALTER LOGIN demo_reader WITH PASSWORD = '\$(pw)';
IF USER_ID('demo_reader') IS NULL CREATE USER demo_reader FOR LOGIN demo_reader;
GRANT SELECT ON cp_metrics_demo TO demo_reader;
GRANT SELECT ON cp_ratios_kv_demo TO demo_reader;
GRANT SELECT ON obligor_metrics_demo TO demo_reader;
UPDATE cov_data_source
   SET connection_config_json = JSON_MODIFY(JSON_MODIFY(connection_config_json,
         '\$.jdbcUrl', 'jdbc:sqlserver://sqlserver:1433;databaseName=covenant;encrypt=true;trustServerCertificate=true'),
         '\$.username', 'demo_reader')
 WHERE type = 'mssql' AND connection_config_json LIKE '%jdbc:h2:%';
UPDATE cov_data_source
   SET connection_config_json = JSON_MODIFY(JSON_MODIFY(connection_config_json,
         '\$.secretRef', 'COVENANT_DS_DEMO_READER_PASSWORD'), '\$.password', NULL)
 WHERE type = 'mssql' AND JSON_VALUE(connection_config_json, '\$.username') = 'demo_reader';"
echo "stack-init done"

SELECT 'Upgrading MetaStore schema from 1.1.0-cdh5.12.0 to 1.1.0-cdh5.16.0' AS MESSAGE;

:r 026-HIVE-19372.mssql.sql

UPDATE VERSION SET SCHEMA_VERSION='1.1.0', VERSION_COMMENT='Hive release version 1.1.0', SCHEMA_VERSION_V2='1.1.0-cdh5.16.0' where VER_ID=1;
SELECT 'Finished upgrading MetaStore schema from 1.1.0-cdh5.12.0 to 1.1.0-cdh5.16.0' AS MESSAGE;

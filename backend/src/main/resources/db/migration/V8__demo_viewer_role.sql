-- Expand the role constraint only; do not create accounts or change existing users.
-- V5 PostgreSQL-generated constraint name is app_user_role_check.
ALTER TABLE operationsflow.app_user DROP CONSTRAINT app_user_role_check;
ALTER TABLE operationsflow.app_user ADD CONSTRAINT app_user_role_check
    CHECK (role IN ('ADMIN', 'WAREHOUSE_OPERATOR', 'SALES_OPERATOR', 'DEMO_VIEWER'));
-- Application rollback requires reassigning ALL viewer rows to an older supported role first:
-- older binaries cannot deserialize DEMO_VIEWER. Never edit applied Flyway history.
